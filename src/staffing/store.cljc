(ns staffing.store
  "SSoT for the temporary-employment-agency actor, behind a `Store`
  protocol so the backend is a swap, not a rewrite:

    - `MemStore`     — atom of Datomic-shaped EDN. The deterministic default
                       for dev/tests/demo (no deps).
    - `DatomicStore` — backed by `langchain.db`, a Datomic-API-compatible EAV
                       store. Pure `.cljc`, so it runs offline AND can be
                       pointed at a real Datomic Local or a kotoba-server pod
                       by swapping `langchain.db`'s `:db-api`.

  Both implement the same protocol and pass the same contract
  (test/staffing/store_contract_test.clj) — the actor, the
  StaffingGovernor and the audit ledger never know which SSoT they run on.

  Entity shapes (ADR-2607111600): a worker (the agency's employer-of-record
  employee), a client (the host company the worker is dispatched to), an
  assignment (worker×client, role, pay-rate, jurisdiction, start/end date —
  the unit the tenure-limit-gate polices), a timesheet (assignment×period,
  hours worked, wage-compliance-gate target), a wage-floor (operator-
  maintained jurisdiction minimum-wage reference — NEVER a hardcoded
  fabricated number, see `staffing.facts`), and a client billing contract
  (tenant × tier, licensed disclosure). There is NO field anywhere in this
  schema for payroll disbursement, bank transfer, or tax withholding
  execution — this actor proposes/approves hours and amounts, it never
  moves money (ADR-2607111600 §1, the same structural exclusion as
  `cloud-itonami-isic-6311`'s market-data actor never trading).

  The ledger stays append-only on every backend — 'who placed/extended/
  approved what, on what eligibility/tenure/wage basis' is always a query
  over an immutable log."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.db :as d]))

(defprotocol Store
  (worker [s id])
  (client [s id])
  (assignment [s id])
  (assignments-of-worker [s worker-id] "all assignments (any status) for this worker, for cumulative tenure calc")
  (timesheet [s id])
  (timesheets-of-assignment [s assignment-id] "all timesheets for this assignment")
  (wage-floor [s jurisdiction])
  (contract [s tenant])
  (ledger [s])
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision/disclosure fact")
  (with-workers [s workers] "replace/seed workers (map id→worker)")
  (with-clients [s clients] "replace/seed clients (map id→client)")
  (with-assignments [s assignments] "replace/seed assignments (map id→assignment)")
  (with-timesheets [s timesheets]   "replace/seed timesheets (map id→timesheet)")
  (with-wage-floors [s floors]      "replace/seed wage floors (map jurisdiction→floor)")
  (with-contracts [s contracts]     "replace/seed client billing contracts (map tenant→contract)"))

;; ───────────────────────── demo data (fictitious, non-real people) ─────

(defn demo-data
  "A small, entirely fictitious dataset so the actor + tests run offline and
  no real worker or client is ever named in this repository. `w-200`'s
  existing assignment is deliberately close to JPN's 36-month cap purely to
  exercise the tenure-limit-gate — it is not a claim about any real person.
  `a-300` carries a demo `:hazardous-duty? true` flag purely to exercise
  the high-risk-assignment governor gate."
  []
  {:workers
   {"w-100" {:id "w-100" :name "田中 花子(デモ)"
             :eligibility {:class :i9-eligibility-verification :ref "i9:demo-w100"}}
    "w-200" {:id "w-200" :name "Kenji Sato (demo)"
             :eligibility {:class :operator-verified-eligibility :ref "jpn-zairyu:demo-w200" :verification-ref "ver-demo-w200"}}
    "w-300" {:id "w-300" :name "Maria Schmidt (demo)"
             :eligibility {:class :operator-verified-eligibility :ref "deu-verify:demo-w300" :verification-ref "ver-demo-w300"}}
    "w-400" {:id "w-400" :name "No-Docs Worker (demo)" :eligibility nil}}
   :clients
   {"c-100" {:id "c-100" :name "デモ製造株式会社" :jurisdiction :jpn}
    "c-200" {:id "c-200" :name "Demo Logistics GmbH" :jurisdiction :deu}
    "c-300" {:id "c-300" :name "Demo Warehousing Inc (USA)" :jurisdiction :usa}}
   :assignments
   {"a-100" {:id "a-100" :worker-id "w-200" :client-id "c-100" :jurisdiction :jpn
             :role "line-worker" :pay-rate 1200M :start-date "2023-08-01" :end-date "2026-06-01"
             :hazardous-duty? false :status :active}
    "a-300" {:id "a-300" :worker-id "w-300" :client-id "c-200" :jurisdiction :deu
             :role "warehouse-forklift-operator" :pay-rate 18.00M
             :start-date "2026-01-01" :end-date "2026-12-01"
             :hazardous-duty? true :status :active}}
   :timesheets
   {"t-100" {:id "t-100" :assignment-id "a-100" :period "2026-07"
             :hours 160M :overtime-hours 0M :approved-amount nil :status :pending}}
   :wage-floors
   {:jpn {:jurisdiction :jpn :hourly-min 1050M :currency :jpy
          :source {:class :jpn-minimum-wage-basis :ref "demo-operator-maintained-rate-table"}}
    :deu {:jurisdiction :deu :hourly-min 12.41M :currency :eur
          :source {:class :aug-tenure-cap :ref "demo-operator-maintained-rate-table"}}
    :usa {:jurisdiction :usa :hourly-min 7.25M :currency :usd
          :source {:class :flsa-wage-basis :ref "demo-operator-maintained-rate-table"}}}
   :contracts
   {"tenant-c100" {:tenant "tenant-c100" :tier :tier/basic :active? true :purpose :billing-review}
    "tenant-c200" {:tenant "tenant-c200" :tier :tier/detailed :active? true :purpose :billing-review}}})

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (worker [_ id] (get-in @a [:workers id]))
  (client [_ id] (get-in @a [:clients id]))
  (assignment [_ id] (get-in @a [:assignments id]))
  (assignments-of-worker [_ worker-id]
    (->> (vals (:assignments @a)) (filter #(= worker-id (:worker-id %))) (sort-by :id)))
  (timesheet [_ id] (get-in @a [:timesheets id]))
  (timesheets-of-assignment [_ assignment-id]
    (->> (vals (:timesheets @a)) (filter #(= assignment-id (:assignment-id %))) (sort-by :id)))
  (wage-floor [_ jurisdiction] (get-in @a [:wage-floors jurisdiction]))
  (contract [_ tenant] (get-in @a [:contracts tenant]))
  (ledger [_] (:ledger @a))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :assignment-upsert (swap! a assoc-in [:assignments (:id value)] value)
      :timesheet-upsert   (swap! a assoc-in [:timesheets (:id value)] value)
      :dispute-apply      (swap! a update-in [:assignments (first path)] merge (:patch value))
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-workers [s ws]     (when (seq ws) (swap! a assoc :workers ws)) s)
  (with-clients [s cs]     (when (seq cs) (swap! a assoc :clients cs)) s)
  (with-assignments [s as] (when (seq as) (swap! a assoc :assignments as)) s)
  (with-timesheets [s ts]  (when (seq ts) (swap! a assoc :timesheets ts)) s)
  (with-wage-floors [s wf] (when (seq wf) (swap! a assoc :wage-floors wf)) s)
  (with-contracts [s cts]  (when (seq cts) (swap! a assoc :contracts cts)) s))

(defn seed-db
  "A MemStore seeded with the demo data. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger []))))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (eligibility citations, source citations) are stored
  as EDN strings so `langchain.db` doesn't expand them into sub-entities."
  {:worker/id      {:db/unique :db.unique/identity}
   :client/id      {:db/unique :db.unique/identity}
   :assignment/id  {:db/unique :db.unique/identity}
   :timesheet/id   {:db/unique :db.unique/identity}
   :wage-floor/jurisdiction {:db/unique :db.unique/identity}
   :contract/tenant {:db/unique :db.unique/identity}
   :ledger/seq     {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- worker->tx [{:keys [id name eligibility]}]
  (cond-> {:worker/id id}
    name        (assoc :worker/name name)
    true        (assoc :worker/eligibility (enc eligibility))))

(defn- pull->worker [m]
  (when (:worker/id m)
    {:id (:worker/id m) :name (:worker/name m) :eligibility (dec* (:worker/eligibility m))}))

(def ^:private worker-pull [:worker/id :worker/name :worker/eligibility])

(defn- client->tx [{:keys [id name jurisdiction]}]
  (cond-> {:client/id id}
    name         (assoc :client/name name)
    jurisdiction (assoc :client/jurisdiction jurisdiction)))

(defn- pull->client [m]
  (when (:client/id m)
    {:id (:client/id m) :name (:client/name m) :jurisdiction (:client/jurisdiction m)}))

(def ^:private client-pull [:client/id :client/name :client/jurisdiction])

(defn- assignment->tx [{:keys [id worker-id client-id jurisdiction role pay-rate start-date end-date hazardous-duty? status]}]
  {:assignment/id id :assignment/worker-id worker-id :assignment/client-id client-id
   :assignment/jurisdiction jurisdiction :assignment/role role
   :assignment/pay-rate (enc pay-rate) :assignment/start-date start-date :assignment/end-date end-date
   :assignment/hazardous (boolean hazardous-duty?) :assignment/status status})

(defn- pull->assignment [m]
  (when (:assignment/id m)
    {:id (:assignment/id m) :worker-id (:assignment/worker-id m) :client-id (:assignment/client-id m)
     :jurisdiction (:assignment/jurisdiction m) :role (:assignment/role m)
     :pay-rate (dec* (:assignment/pay-rate m)) :start-date (:assignment/start-date m)
     :end-date (:assignment/end-date m) :hazardous-duty? (:assignment/hazardous m)
     :status (:assignment/status m)}))

(def ^:private assignment-pull
  [:assignment/id :assignment/worker-id :assignment/client-id :assignment/jurisdiction
   :assignment/role :assignment/pay-rate :assignment/start-date :assignment/end-date
   :assignment/hazardous :assignment/status])

(defn- timesheet->tx [{:keys [id assignment-id period hours overtime-hours approved-amount status]}]
  {:timesheet/id id :timesheet/assignment-id assignment-id :timesheet/period period
   :timesheet/hours (enc hours) :timesheet/overtime-hours (enc overtime-hours)
   :timesheet/approved-amount (enc approved-amount) :timesheet/status status})

(defn- pull->timesheet [m]
  (when (:timesheet/id m)
    {:id (:timesheet/id m) :assignment-id (:timesheet/assignment-id m) :period (:timesheet/period m)
     :hours (dec* (:timesheet/hours m)) :overtime-hours (dec* (:timesheet/overtime-hours m))
     :approved-amount (dec* (:timesheet/approved-amount m)) :status (:timesheet/status m)}))

(def ^:private timesheet-pull
  [:timesheet/id :timesheet/assignment-id :timesheet/period :timesheet/hours
   :timesheet/overtime-hours :timesheet/approved-amount :timesheet/status])

(defn- wage-floor->tx [{:keys [jurisdiction hourly-min currency source]}]
  {:wage-floor/jurisdiction jurisdiction :wage-floor/hourly-min (enc hourly-min)
   :wage-floor/currency currency :wage-floor/source (enc source)})

(defn- pull->wage-floor [m]
  (when (:wage-floor/jurisdiction m)
    {:jurisdiction (:wage-floor/jurisdiction m) :hourly-min (dec* (:wage-floor/hourly-min m))
     :currency (:wage-floor/currency m) :source (dec* (:wage-floor/source m))}))

(def ^:private wage-floor-pull
  [:wage-floor/jurisdiction :wage-floor/hourly-min :wage-floor/currency :wage-floor/source])

(defn- contract->tx [{:keys [tenant tier active? purpose]}]
  {:contract/tenant tenant :contract/tier tier :contract/active active? :contract/purpose purpose})

(defn- pull->contract [m]
  (when (:contract/tenant m)
    {:tenant (:contract/tenant m) :tier (:contract/tier m)
     :active? (:contract/active m) :purpose (:contract/purpose m)}))

(def ^:private contract-pull
  [:contract/tenant :contract/tier :contract/active :contract/purpose])

(defrecord DatomicStore [conn]
  Store
  (worker [_ id] (pull->worker (d/pull (d/db conn) worker-pull [:worker/id id])))
  (client [_ id] (pull->client (d/pull (d/db conn) client-pull [:client/id id])))
  (assignment [_ id] (pull->assignment (d/pull (d/db conn) assignment-pull [:assignment/id id])))
  (assignments-of-worker [_ worker-id]
    (->> (d/q '[:find [?id ...] :in $ ?wid
                :where [?e :assignment/worker-id ?wid] [?e :assignment/id ?id]]
              (d/db conn) worker-id)
         (map #(pull->assignment (d/pull (d/db conn) assignment-pull [:assignment/id %])))
         (sort-by :id)))
  (timesheet [_ id] (pull->timesheet (d/pull (d/db conn) timesheet-pull [:timesheet/id id])))
  (timesheets-of-assignment [_ assignment-id]
    (->> (d/q '[:find [?id ...] :in $ ?aid
                :where [?e :timesheet/assignment-id ?aid] [?e :timesheet/id ?id]]
              (d/db conn) assignment-id)
         (map #(pull->timesheet (d/pull (d/db conn) timesheet-pull [:timesheet/id %])))
         (sort-by :id)))
  (wage-floor [_ jurisdiction]
    (pull->wage-floor (d/pull (d/db conn) wage-floor-pull [:wage-floor/jurisdiction jurisdiction])))
  (contract [_ tenant] (pull->contract (d/pull (d/db conn) contract-pull [:contract/tenant tenant])))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :assignment-upsert (d/transact! conn [(assignment->tx value)])
      :timesheet-upsert   (d/transact! conn [(timesheet->tx value)])
      :dispute-apply
      (d/transact! conn [(assignment->tx (merge (assignment s (first path)) (:patch value)))])
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-workers [s ws]
    (when (seq ws) (d/transact! conn (mapv worker->tx (vals ws)))) s)
  (with-clients [s cs]
    (when (seq cs) (d/transact! conn (mapv client->tx (vals cs)))) s)
  (with-assignments [s as]
    (when (seq as) (d/transact! conn (mapv assignment->tx (vals as)))) s)
  (with-timesheets [s ts]
    (when (seq ts) (d/transact! conn (mapv timesheet->tx (vals ts)))) s)
  (with-wage-floors [s wf]
    (when (seq wf) (d/transact! conn (mapv wage-floor->tx (vals wf)))) s)
  (with-contracts [s cts]
    (when (seq cts) (d/transact! conn (mapv contract->tx (vals cts)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`; empty when
  omitted."
  ([] (datomic-store {}))
  ([{:keys [workers clients assignments timesheets wage-floors contracts]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-workers workers) (with-clients clients)
         (with-assignments assignments) (with-timesheets timesheets)
         (with-wage-floors wage-floors) (with-contracts contracts)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo data — the Datomic-backed analog of
  `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))

;; ───────────────────────── ledger formatting ─────────────────────────

(defn ledger-line
  "Human-readable one-liner for a ledger fact (used by the demo)."
  [{:keys [op actor subject disposition basis]}]
  (str/join " · "
            [(name disposition)
             (str "op=" op)
             (str "actor=" actor)
             (str "subject=" subject)
             (str "basis=" (pr-str basis))]))
