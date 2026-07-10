(ns staffing.policy
  "StaffingGovernor — the independent compliance layer that earns the
  TempStaffing-LLM the right to place/extend an assignment, approve a
  timesheet, or serve a client report. The LLM has no notion of statutory
  tenure caps, wage-floor compliance, or a client's disclosure entitlement,
  so this MUST be a separate system able to *reject* a proposal and fall
  back to HOLD (place/extend/approve/disclose nothing) — this actor's
  analog of `cloud-itonami-isic-8291`'s DisclosureGovernor and
  `cloud-itonami-isic-6311`'s MarketDataGovernor.

  Eight checks, in priority order. The first five are HARD violations: a
  human approver CANNOT override them. The last three are SOFT/always-
  escalate: they route to a human, who may approve.

    1. rbac                  — does actor-role have permission for op?
    2. eligibility-gate       — does the worker's cited eligibility resolve
                                to an allowed class (and, for the
                                operator-attested structural class, carry a
                                verification-ref)?
    3. tenure-limit-gate      — would this placement/extension push the
                                worker's cumulative assignment duration at
                                this client past the jurisdiction's
                                statutory cap? (this actor's domain-unique
                                HARD check, no analog in any sibling actor)
    4. wage-compliance-gate   — does the approved timesheet's effective
                                hourly rate meet the jurisdiction's
                                operator-maintained wage floor?
    5. licensed-disclosure    — is there an active client billing contract,
                                and does the requested report stay within
                                its tier?
    6. confidence floor       — LLM confidence below threshold → escalate.
    7. high-risk-assignment gate — the assignment is hazardous-duty →
                                always escalate, regardless of confidence.
    8. dispute requests       — a worker/client dispute NEVER auto-resolves,
                                at any confidence, any phase."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [staffing.facts :as facts]
            [staffing.store :as store]))

;; ───────────────────────── policy tables ─────────────────────────

(def confidence-floor 0.6)

(def permissions
  "actor-role → set of operations it may perform."
  {:staffing-coordinator #{:assignment/place :assignment/extend}
   :payroll-officer      #{:timesheet/approve}
   :dispute-officer      #{:dispute/request}
   :client-user          #{:report/query}})

(def tier-columns
  "For `:report/query` — the columns each licensed client-billing tier may
  see. Anything beyond this is over-disclosure (licensed-disclosure
  violation), the staffing analog of `dossier`/`marketdata`'s tier tables."
  (let [base #{:assignment-id :role :period :hours :approved-amount}
        detailed-extra #{:worker-id :pay-rate}
        audit-extra #{:eligibility-status :jurisdiction-basis}]
    {:tier/basic    base
     :tier/detailed (into base detailed-extra)
     :tier/audit    (into base (into detailed-extra audit-extra))}))

;; ───────────────────────── helpers ─────────────────────────

(defn- year-month [date-str]
  (let [[y m] (str/split (subs date-str 0 7) #"-")]
    [(parse-long y) (parse-long m)]))

(defn months-between
  "Whole-month duration between two `YYYY-MM-DD`/`YYYY-MM` date strings,
  ignoring day-of-month precision — deterministic and sufficient for a
  statutory-cap comparison (a real operator deployment would use exact
  calendar arithmetic; this repo's R0 scope keeps it simple and honest
  about that simplification, see docs/DESIGN.md)."
  [start-date end-date]
  (let [[y1 m1] (year-month start-date)
        [y2 m2] (year-month end-date)]
    (+ (* 12 (- y2 y1)) (- m2 m1))))

;; ───────────────────────── checks ─────────────────────────

(defn- rbac-violations [{:keys [op]} {:keys [actor-role]}]
  (when-not (contains? (get permissions actor-role #{}) op)
    [{:rule :rbac :detail (str actor-role " は " op " の権限を持たない")}]))

(defn- eligibility-violations
  [{:keys [op]} proposal]
  (when (contains? #{:assignment/place :assignment/extend} op)
    (let [src (:source proposal)]
      (cond
        (or (nil? src) (not (facts/class-allowed? (:class src))))
        [{:rule :eligibility-gate
          :detail (str "worker の eligibility 出典が無いか許可されたクラスでない: " (pr-str src))}]

        (and (facts/operator-verified-class? (:class src)) (nil? (:verification-ref src)))
        [{:rule :eligibility-gate
          :detail "operator-verified-eligibility は :verification-ref を要求する"}]

        :else nil))))

(defn- tenure-limit-violations
  [{:keys [op]} proposal st]
  (when (contains? #{:assignment/place :assignment/extend} op)
    (let [{:keys [worker-id client-id jurisdiction end-date id]} (:value proposal)
          cap (get facts/tenure-cap-months jurisdiction)]
      (when cap
        (let [others (->> (store/assignments-of-worker st worker-id)
                          (filter #(= client-id (:client-id %)))
                          (remove #(= id (:id %))))
              other-months (reduce + 0 (map #(months-between (:start-date %) (:end-date %)) others))
              this-months  (months-between (:start-date (:value proposal)) end-date)
              total (+ other-months this-months)]
          (when (> total cap)
            [{:rule :tenure-limit-gate
              :detail (str "累計継続期間が法定上限を超過: jurisdiction=" jurisdiction
                           " total-months=" total " > cap=" cap)}]))))))

(defn- wage-compliance-violations
  [{:keys [op]} proposal st]
  (when (= op :timesheet/approve)
    (let [{:keys [assignment-id hours overtime-hours approved-amount]} (:value proposal)
          asg (store/assignment st assignment-id)
          floor (store/wage-floor st (:jurisdiction asg))
          total-hours (+ (double (or hours 0M)) (* (double (or overtime-hours 0M)) 1.5))]
      (cond
        (nil? floor)
        [{:rule :wage-compliance-gate
          :detail (str "jurisdiction " (:jurisdiction asg) " の wage-floor が未登録")}]

        (and (pos? total-hours) approved-amount)
        (let [effective (/ (double approved-amount) total-hours)]
          (when (< effective (double (:hourly-min floor)))
            [{:rule :wage-compliance-gate
              :detail (str "実効時給が最低賃金未満: effective=" effective
                           " < floor=" (:hourly-min floor))}]))

        :else nil))))

(defn- licensed-disclosure-violations
  [{:keys [op]} {:keys [tenant]} proposal st]
  (when (= op :report/query)
    (let [c (when tenant (store/contract st tenant))]
      (if (or (nil? c) (not (:active? c)))
        [{:rule :licensed-disclosure :detail (str "有効な契約が無い: tenant=" tenant)}]
        (let [allowed (get tier-columns (:tier c) #{})
              cols    (set (:columns proposal))
              extra   (set/difference cols allowed)]
          (when (seq extra)
            [{:rule :licensed-disclosure
              :detail (str "契約 tier " (:tier c) " に対し過剰な列: " (vec extra))}]))))))

(defn- hazardous?
  [proposal]
  (boolean (get-in proposal [:value :hazardous-duty?])))

(defn check
  "Censors a TempStaffing-LLM proposal against the policy tables. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :hazardous? bool
    :hard? bool :dispute? bool}.

   - :hard?       — at least one HARD violation (rbac/eligibility-gate/
                    tenure-limit-gate/wage-compliance-gate/
                    licensed-disclosure). Forces HOLD; a human cannot
                    override.
   - :escalate?   — soft: low confidence, hazardous-duty assignment, OR a
                    dispute request. A human decides.
   - :ok?         — clean AND not escalating: safe to auto-commit/-serve."
  [request context proposal st]
  (let [hard    (into []
                      (concat (rbac-violations request context)
                              (eligibility-violations request proposal)
                              (tenure-limit-violations request proposal st)
                              (wage-compliance-violations request proposal st)
                              (licensed-disclosure-violations request context proposal st)))
        conf        (:confidence proposal 0.0)
        low?        (< conf confidence-floor)
        hazardous?  (and (= :assignment/place (:op request)) (hazardous? proposal))
        dispute?    (= :dispute/request (:op request))
        hard?       (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not hazardous?) (not dispute?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? hazardous? dispute?))
     :hazardous?   hazardous?
     :dispute?     dispute?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :policy-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
