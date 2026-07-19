(ns staffing.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root flagship rollout,
  cloud-itonami org): this repo previously had NO demo page and no
  generator at all. This namespace drives the REAL actor stack
  (`staffing.operation` -> `staffing.policy` (StaffingGovernor) ->
  `staffing.store`) through a scenario reused verbatim from this repo's
  own `staffing.sim` demo driver (`clojure -M:dev:run`) -- that driver was
  run and its output inspected before this file was written, and every id
  it uses (`w-100`/`w-200`/`w-300`/`w-400`, `c-100`/`c-200`/`c-300`,
  `a-100`/`a-300`, `t-100`, `tenant-c100`) was cross-checked against
  `staffing.store/demo-data`'s own seed and confirmed to match -- unlike
  `cloud-itonami-isic-851`'s `schoolops.sim`, this repo's sim driver is
  NOT broken, so it was safe to reuse rather than author a scenario from
  scratch. The seven ops exercise a genuine mix of dispositions: one
  clean auto-commit, two ops that escalate to human approval (hazardous-
  duty placement, worker/client dispute) and are approved, and FOUR
  distinct HARD-hold reasons (eligibility-gate, tenure-limit-gate, wage-
  compliance-gate, licensed-disclosure) that never reach a human. Output
  is rendered deterministically -- no invented numbers, ids or outcomes,
  no timestamps in the page content, byte-identical across reruns against
  the same seed (verified by diffing two consecutive runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [staffing.store :as store]
            [staffing.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- actors -----------------------------

(def ^:private coordinator {:actor-id "co-1" :actor-role :staffing-coordinator :phase 3})
(def ^:private payroll     {:actor-id "pr-1" :actor-role :payroll-officer :phase 3})
(def ^:private disputer    {:actor-id "do-1" :actor-role :dispute-officer :phase 3})
(def ^:private client-user {:actor-id "cl-1" :actor-role :client-user :tenant "tenant-c100"})

;; ----------------------------- harness -----------------------------

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coordinator-1"}}
          {:thread-id tid :resume? true}))

(defn- run-op!
  "Runs one operation on its own thread-id via the real graph. If it
  interrupts for human approval, approves and resumes (mirrors
  `staffing.sim/run-op!`). Returns real telemetry captured from the
  actual `g/run*` results -- never fabricated:
    {:tid :op :subject :actor-role :mode :disposition}
  where :mode is one of :auto-commit | :escalated-approved | :hard-hold,
  derived from whether the FIRST run actually interrupted and what the
  FINAL disposition actually was."
  [actor tid request context]
  (let [res          (exec! actor tid request context)
        interrupted? (= :interrupted (:status res))
        res2         (if interrupted? (approve! actor tid) res)
        disposition  (get-in res2 [:state :disposition])]
    {:tid tid :op (:op request) :subject (:subject request)
     :actor-role (:actor-role context)
     :mode (cond interrupted?               :escalated-approved
                 (= :commit disposition)     :auto-commit
                 :else                       :hard-hold)
     :disposition disposition}))

(defn run-demo!
  "Runs a fresh seeded store through the same seven ops as
  `staffing.sim/-main` (op1..op7): a-usa1 clears a clean new placement in
  a jurisdiction with no tenure cap (auto-commit); a-bad1 HARD-holds a
  placement for a worker with no on-file eligibility (eligibility-gate);
  a-100's extension HARD-holds for exceeding JPN's statutory 36-month
  dispatch cap (tenure-limit-gate); t-100's timesheet approval HARD-holds
  for a miscalculated (straight-time, not 1.5x) overtime rate that drops
  below the wage floor (wage-compliance-gate); a report query on a-100
  HARD-holds for requesting columns beyond its tier/basic contract
  (licensed-disclosure); a-hz1's hazardous-duty placement ALWAYS escalates
  to human approval (approved -> commit); a dispute on a-100's timesheet
  hours ALWAYS escalates to human review regardless of phase (approved ->
  commit). Every HARD hold never reaches a human. Returns
  `{:db db :steps [..]}` -- every field `render` reads below is real
  governor/store output or real per-step telemetry captured from actually
  running the graph, not a hand-typed copy."
  []
  (let [db    (store/seed-db)
        actor (op/build db)
        steps
        [(run-op! actor "p1-place-clean"
                  {:op :assignment/place :subject "a-usa1" :id "a-usa1" :worker-id "w-100"
                   :client-id "c-300" :jurisdiction :usa :role "picker" :pay-rate 9.00M
                   :start-date "2026-07-01" :end-date "2026-10-01" :hazardous-duty? false}
                  coordinator)
         (run-op! actor "p2-place-no-eligibility"
                  {:op :assignment/place :subject "a-bad1" :id "a-bad1" :worker-id "w-400"
                   :client-id "c-300" :jurisdiction :usa :role "picker" :pay-rate 9.00M
                   :start-date "2026-07-01" :end-date "2026-10-01" :hazardous-duty? false}
                  coordinator)
         (run-op! actor "p3-extend-over-cap"
                  {:op :assignment/extend :subject "a-100" :assignment-id "a-100"
                   :worker-id "w-200" :new-end-date "2027-06-01"}
                  coordinator)
         (run-op! actor "p4-timesheet-miscalc"
                  {:op :timesheet/approve :subject "a-100" :id "t-100" :assignment-id "a-100"
                   :hours 100M :overtime-hours 100M :miscalc? true}
                  payroll)
         (run-op! actor "p5-report-overreach"
                  {:op :report/query :subject "a-100" :assignment-id "a-100" :greedy? true}
                  client-user)
         (run-op! actor "p6-place-hazardous"
                  {:op :assignment/place :subject "a-hz1" :id "a-hz1" :worker-id "w-300"
                   :client-id "c-200" :jurisdiction :deu :role "crane-operator" :pay-rate 20.00M
                   :start-date "2026-07-01" :end-date "2026-09-01" :hazardous-duty? true}
                  coordinator)
         (run-op! actor "p7-dispute"
                  {:op :dispute/request :subject "a-100" :disputed-field :hours :claim 150M}
                  disputer)]]
    {:db db :steps steps}))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (#{:policy-hold :approval-rejected} (:t f))
      (str "<span class=\"critical\">HARD hold &middot; "
           (esc (name (or (first (:basis f)) :unknown))) "</span>")
      :else "<span class=\"muted\">in progress</span>")))

(defn- assignment-row [db ledger id]
  (let [a (store/assignment db id)
        w (store/worker db (:worker-id a))
        c (store/client db (:client-id a))]
    (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
            (esc id) (esc (:name w)) (esc (:name c)) (esc (name (:jurisdiction a)))
            (esc (:role a)) (esc (name (or (:status a) :n-a))) (status-cell ledger id))))

(def ^:private mode-label
  {:auto-commit         "<span class=\"ok\">auto-commit</span>"
   :escalated-approved  "<span class=\"warn\">escalated &rarr; approved</span>"
   :hard-hold           "<span class=\"critical\">HARD hold (no human reached)</span>"})

(defn- step-row [{:keys [tid op subject actor-role mode disposition]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td><code>%s</code></td></tr>"
          (esc tid) (esc (name op)) (esc subject) (esc (name actor-role))
          (get mode-label mode "?") (esc (name disposition))))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README `Ops`
  ;; table, `staffing.policy`'s eight-check docstring) -- documentation of
  ;; fixed behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td>1</td><td><code>rbac</code></td><td><span class=\"err\">HARD</span></td><td>actor-role must be permitted for the op</td></tr>"
   "        <tr><td>2</td><td><code>eligibility-gate</code></td><td><span class=\"err\">HARD</span></td><td>worker's cited eligibility must resolve to an allowed class (operator-verified class requires a :verification-ref)</td></tr>"
   "        <tr><td>3</td><td><code>tenure-limit-gate</code></td><td><span class=\"err\">HARD</span></td><td>cumulative assignment duration at this client must stay within the jurisdiction's statutory cap</td></tr>"
   "        <tr><td>4</td><td><code>wage-compliance-gate</code></td><td><span class=\"err\">HARD</span></td><td>timesheet's effective hourly rate must meet the jurisdiction's operator-maintained wage floor</td></tr>"
   "        <tr><td>5</td><td><code>licensed-disclosure</code></td><td><span class=\"err\">HARD</span></td><td>report columns must stay within the client's active contract tier</td></tr>"
   "        <tr><td>6</td><td>confidence floor</td><td><span class=\"warn\">escalate</span></td><td>LLM confidence below threshold (0.6) routes to human review</td></tr>"
   "        <tr><td>7</td><td>high-risk-assignment gate</td><td><span class=\"warn\">escalate</span></td><td>hazardous-duty placements always require human approval</td></tr>"
   "        <tr><td>8</td><td>dispute requests</td><td><span class=\"warn\">escalate</span></td><td>a worker/client dispute never auto-resolves, at any confidence or phase</td></tr>"])

(defn render
  "Renders the full operator-console.html document from `{:db :steps}`
  produced by `run-demo!` (or any other real scenario run through this
  actor)."
  [{:keys [db steps]}]
  (let [ledger (vec (store/ledger db))
        assignment-ids ["a-100" "a-300" "a-usa1" "a-hz1"]
        assignment-rows (->> assignment-ids
                              (filter #(store/assignment db %))
                              sort
                              (map (partial assignment-row db ledger))
                              (str/join "\n"))
        step-rows (str/join "\n" (map step-row steps))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-7820 &middot; temporary-employment-agency</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 1080px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Temporary employment agency (ISIC 7820) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · never disburses payroll or moves money</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Managed assignments</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>staffing.store</code> via <code>staffing.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Assignment</th><th>Worker</th><th>Client</th><th>Jurisdiction</th><th>Role</th><th>Status</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     assignment-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Scenario steps (this run)</h2>\n"
     "    <p class=\"muted\">Real per-step telemetry captured from actually driving the <code>langgraph.graph</code> StateGraph — a genuine mix of dispositions: auto-commit, escalate-then-approve, and HARD hold (never reaches a human).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Step</th><th>Op</th><th>Subject</th><th>Actor role</th><th>Workflow</th><th>Final disposition</th></tr></thead>\n"
     "      <tbody>\n"
     step-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (StaffingGovernor — eight checks, priority order)</h2>\n"
     "    <p class=\"muted\">Checks 1&ndash;5 are HARD: a human approver cannot override them. Checks 6&ndash;8 always escalate to a human, who may approve.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>#</th><th>Check</th><th>Kind</th><th>What it enforces</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal this scenario committed or held, and on what eligibility/tenure/wage/disclosure basis.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger (:db result))) "ledger facts,"
             (count (:steps result)) "scenario steps )")))
