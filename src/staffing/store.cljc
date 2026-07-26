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
  employee), a CANDIDATE (someone being considered for employment, not yet
  employed — see below), a client (the host company the worker is
  dispatched to), an
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

  A candidate is `{:id :handle :provenance :claimed-skills :available-from
  :location-scope :contact-ref :status}` in its OWN container, never in
  `:workers`. Three consequences, all structural:

    1. `worker`/`assignments-of-worker` cannot return someone who was never
       hired, so a placement aimed at a candidate is rejected outright
       (`staffing.policy`'s unknown-worker gate) rather than depending on a
       status filter every caller must remember.
    2. A candidate carries NO personal identifiers: a self-chosen
       `:handle`, an opaque `:contact-ref` pointing at the conversation
       that already exists (e.g. a GitHub issue), and what they say they
       can do. The employee's legal `:name` and eligibility citation enter
       only on `:candidate-hire`, which no phase can auto-commit — a human
       enters them at sign-off. There is still no field anywhere for a bank
       account or tax id (ADR-2607111600 §1: this actor never moves money).
    3. `:provenance` records HOW the candidate arrived — a direct
       application, or a human-carried referral draft from a sibling actor
       (`{:kind :referral-draft :from-actor \"cloud-itonami-isco-7126\"
       :draft-id \"...\"}`), which is the seam ADR-2607131000 /
       ADR-2607202600 define. It is a claim about provenance recorded on
       THIS side of the seam; this actor never calls the other actor to
       confirm the draft exists, because that cross-actor invocation is
       exactly what those ADRs forbid.

  The ledger stays append-only on every backend — 'who placed/extended/
  approved what, on what eligibility/tenure/wage basis' is always a query
  over an immutable log."
  (:require [clojure.string :as str]
            [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (worker [s id])
  (client [s id])
  (assignment [s id])
  (assignments-of-worker [s worker-id] "all assignments (any status) for this worker, for cumulative tenure calc")
  (timesheet [s id])
  (timesheets-of-assignment [s assignment-id] "all timesheets for this assignment")
  (wage-floor [s jurisdiction])
  (candidate [s id] "someone being considered for employment here — NEVER an employed worker (see ns docstring)")
  (all-candidates [s])
  (contract [s tenant])
  (ledger [s])
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision/disclosure fact")
  (with-workers [s workers] "replace/seed workers (map id→worker)")
  (with-clients [s clients] "replace/seed clients (map id→client)")
  (with-assignments [s assignments] "replace/seed assignments (map id→assignment)")
  (with-timesheets [s timesheets]   "replace/seed timesheets (map id→timesheet)")
  (with-wage-floors [s floors]      "replace/seed wage floors (map jurisdiction→floor)")
  (with-candidates [s candidates]   "replace/seed hiring candidates (map id→candidate)")
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
   ;; One pending candidate, arrived as a human-carried referral draft from
   ;; an isco actor whose robot could not do the on-site work itself
   ;; (ADR-2607202600's on-site-recurring routing branch). No legal name,
   ;; no contact detail -- a handle and a pointer to the public thread.
   :candidates
   {"cd-100" {:id "cd-100" :handle "haruki (demo)"
              :provenance {:kind :referral-draft
                           :from-actor "cloud-itonami-isco-7126"
                           :draft-id "draft-demo-0001"}
              :claimed-skills #{:on-site-install :equipment-teardown}
              :available-from "2026-08-01"
              :location-scope :per-engagement
              :contact-ref "gh-issue:cloud-itonami/cloud-itonami-isic-6399#0"
              :status :candidate}}
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
  (candidate [_ id] (get-in @a [:candidates id]))
  (all-candidates [_] (sort-by :id (vals (:candidates @a))))
  (contract [_ tenant] (get-in @a [:contracts tenant]))
  (ledger [_] (:ledger @a))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :assignment-upsert (swap! a assoc-in [:assignments (:id value)] value)
      :timesheet-upsert   (swap! a assoc-in [:timesheets (:id value)] value)
      :candidate-upsert   (swap! a update-in [:candidates (:id value)] merge value)
      ;; Hiring is the only path into :workers. The candidate record is
      ;; kept (marked :hired) so 'who was hired/declined, on whose
      ;; sign-off, on what eligibility basis' stays a ledger query.
      :candidate-hire     (let [{:keys [candidate-id name eligibility]} value]
                            (swap! a assoc-in [:candidates candidate-id :status] :hired)
                            (swap! a assoc-in [:workers candidate-id]
                                   {:id candidate-id :name name :eligibility eligibility}))
      :candidate-decline  (swap! a update-in [:candidates (:candidate-id value)]
                                 merge {:status :declined :decline-reason (:reason value)})
      :dispute-apply      (swap! a update-in [:assignments (first path)] merge (:patch value))
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-workers [s ws]     (when (seq ws) (swap! a assoc :workers ws)) s)
  (with-clients [s cs]     (when (seq cs) (swap! a assoc :clients cs)) s)
  (with-assignments [s as] (when (seq as) (swap! a assoc :assignments as)) s)
  (with-timesheets [s ts]  (when (seq ts) (swap! a assoc :timesheets ts)) s)
  (with-wage-floors [s wf] (when (seq wf) (swap! a assoc :wage-floors wf)) s)
  (with-candidates [s cs]  (when (seq cs) (swap! a assoc :candidates cs)) s)
  (with-contracts [s cts]  (when (seq cts) (swap! a assoc :contracts cts)) s))

(defn seed-db
  "A MemStore seeded with the demo data. The deterministic default."
  []
  (->MemStore (atom (merge {:candidates {} :ledger []} (demo-data)))))

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
   :candidate/id   {:db/unique :db.unique/identity}
   :contract/tenant {:db/unique :db.unique/identity}
   :ledger/seq     {:db/unique :db.unique/identity}})

(defn- worker->tx [{:keys [id name eligibility]}]
  (cond-> {:worker/id id}
    name        (assoc :worker/name name)
    true        (assoc :worker/eligibility (ls/enc eligibility))))

(defn- pull->worker [m]
  (when (:worker/id m)
    {:id (:worker/id m) :name (:worker/name m) :eligibility (ls/dec* (:worker/eligibility m))}))

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
   :assignment/pay-rate (ls/enc pay-rate) :assignment/start-date start-date :assignment/end-date end-date
   :assignment/hazardous (boolean hazardous-duty?) :assignment/status status})

(defn- pull->assignment [m]
  (when (:assignment/id m)
    {:id (:assignment/id m) :worker-id (:assignment/worker-id m) :client-id (:assignment/client-id m)
     :jurisdiction (:assignment/jurisdiction m) :role (:assignment/role m)
     :pay-rate (ls/dec* (:assignment/pay-rate m)) :start-date (:assignment/start-date m)
     :end-date (:assignment/end-date m) :hazardous-duty? (:assignment/hazardous m)
     :status (:assignment/status m)}))

(def ^:private assignment-pull
  [:assignment/id :assignment/worker-id :assignment/client-id :assignment/jurisdiction
   :assignment/role :assignment/pay-rate :assignment/start-date :assignment/end-date
   :assignment/hazardous :assignment/status])

(defn- timesheet->tx [{:keys [id assignment-id period hours overtime-hours approved-amount status]}]
  {:timesheet/id id :timesheet/assignment-id assignment-id :timesheet/period period
   :timesheet/hours (ls/enc hours) :timesheet/overtime-hours (ls/enc overtime-hours)
   :timesheet/approved-amount (ls/enc approved-amount) :timesheet/status status})

(defn- pull->timesheet [m]
  (when (:timesheet/id m)
    {:id (:timesheet/id m) :assignment-id (:timesheet/assignment-id m) :period (:timesheet/period m)
     :hours (ls/dec* (:timesheet/hours m)) :overtime-hours (ls/dec* (:timesheet/overtime-hours m))
     :approved-amount (ls/dec* (:timesheet/approved-amount m)) :status (:timesheet/status m)}))

(def ^:private timesheet-pull
  [:timesheet/id :timesheet/assignment-id :timesheet/period :timesheet/hours
   :timesheet/overtime-hours :timesheet/approved-amount :timesheet/status])

(defn- wage-floor->tx [{:keys [jurisdiction hourly-min currency source]}]
  {:wage-floor/jurisdiction jurisdiction :wage-floor/hourly-min (ls/enc hourly-min)
   :wage-floor/currency currency :wage-floor/source (ls/enc source)})

(defn- pull->wage-floor [m]
  (when (:wage-floor/jurisdiction m)
    {:jurisdiction (:wage-floor/jurisdiction m) :hourly-min (ls/dec* (:wage-floor/hourly-min m))
     :currency (:wage-floor/currency m) :source (ls/dec* (:wage-floor/source m))}))

(def ^:private wage-floor-pull
  [:wage-floor/jurisdiction :wage-floor/hourly-min :wage-floor/currency :wage-floor/source])

(defn- candidate->tx [{:keys [id handle provenance claimed-skills available-from
                             location-scope contact-ref status decline-reason]}]
  (cond-> {:candidate/id id}
    handle          (assoc :candidate/handle handle)
    true            (assoc :candidate/provenance (ls/enc provenance))
    true            (assoc :candidate/claimed-skills (ls/enc (or claimed-skills #{})))
    available-from  (assoc :candidate/available-from available-from)
    location-scope  (assoc :candidate/location-scope location-scope)
    ;; opaque pointer, not a contact detail -- encrypted at rest all the
    ;; same, the same treatment eligibility citations get.
    contact-ref     (assoc :candidate/contact-ref (ls/enc contact-ref))
    status          (assoc :candidate/status status)
    decline-reason  (assoc :candidate/decline-reason decline-reason)))

(defn- pull->candidate [m]
  (when (:candidate/id m)
    {:id (:candidate/id m) :handle (:candidate/handle m)
     :provenance (ls/dec* (:candidate/provenance m))
     :claimed-skills (or (ls/dec* (:candidate/claimed-skills m)) #{})
     :available-from (:candidate/available-from m)
     :location-scope (:candidate/location-scope m)
     :contact-ref (ls/dec* (:candidate/contact-ref m))
     :status (:candidate/status m)
     :decline-reason (:candidate/decline-reason m)}))

(def ^:private candidate-pull
  [:candidate/id :candidate/handle :candidate/provenance :candidate/claimed-skills
   :candidate/available-from :candidate/location-scope :candidate/contact-ref
   :candidate/status :candidate/decline-reason])

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
  (candidate [_ id] (pull->candidate (d/pull (d/db conn) candidate-pull [:candidate/id id])))
  (all-candidates [_]
    (->> (d/q '[:find [?id ...] :where [?e :candidate/id ?id]] (d/db conn))
         (map #(pull->candidate (d/pull (d/db conn) candidate-pull [:candidate/id %])))
         (sort-by :id)))
  (contract [_ tenant] (pull->contract (d/pull (d/db conn) contract-pull [:contract/tenant tenant])))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :assignment-upsert (d/transact! conn [(assignment->tx value)])
      :timesheet-upsert   (d/transact! conn [(timesheet->tx value)])
      :candidate-upsert   (d/transact! conn [(candidate->tx value)])
      :candidate-hire     (let [{:keys [candidate-id name eligibility]} value
                                c (candidate s candidate-id)]
                            (d/transact! conn [(candidate->tx (assoc c :status :hired))
                                               (worker->tx {:id candidate-id :name name
                                                            :eligibility eligibility})]))
      :candidate-decline  (let [c (candidate s (:candidate-id value))]
                            (d/transact! conn [(candidate->tx (merge c {:status :declined
                                                                        :decline-reason (:reason value)}))]))
      :dispute-apply
      (d/transact! conn [(assignment->tx (merge (assignment s (first path)) (:patch value)))])
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (ls/enc fact)}])
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
  (with-candidates [s cs]
    (when (seq cs) (d/transact! conn (mapv candidate->tx (vals cs)))) s)
  (with-contracts [s cts]
    (when (seq cts) (d/transact! conn (mapv contract->tx (vals cts)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`; empty when
  omitted."
  ([] (datomic-store {}))
  ([{:keys [workers clients assignments timesheets wage-floors candidates contracts]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-workers workers) (with-clients clients)
         (with-assignments assignments) (with-timesheets timesheets)
         (with-wage-floors wage-floors) (with-candidates candidates)
         (with-contracts contracts)))))

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
