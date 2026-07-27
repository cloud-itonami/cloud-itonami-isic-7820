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
    5. unknown-worker         — is the placement's worker actually employed
                                here? (also what keeps a dispatch away from
                                someone only recorded as a candidate)
    6. provenance-gate        — does a candidate record state how the person
                                arrived (direct application, or a referral
                                draft naming a fleet actor + draft id)?
    7. candidate-lifecycle    — candidate → hired | declined, exactly once.
    8. scope-gate             — does the proposal carry a personal
                                identifier or a payment/tax field the schema
                                excludes structurally?
    9. licensed-disclosure    — is there an active client billing contract,
                                and does the requested report stay within
                                its tier?
   10. confidence floor       — LLM confidence below threshold → escalate.
   11. high-risk-assignment gate — the assignment is hazardous-duty →
                                always escalate, regardless of confidence.
   12. hiring ops             — `:worker/hire`/`:worker/decline` NEVER
                                auto-commit at any phase or confidence.
                                Becoming a real person's employer of record
                                (or turning them down) is a human decision
                                this actor only prepares, governs, records.
   13. dispute requests       — a worker/client dispute NEVER auto-resolves,
                                at any confidence, any phase."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [staffing.facts :as facts]
            [staffing.store :as store]))

;; ───────────────────────── policy tables ─────────────────────────

(def confidence-floor 0.6)

(def permissions
  "actor-role → set of operations it may perform."
  {:staffing-coordinator #{:assignment/place :assignment/extend :candidate/intake}
   ;; Hiring is what makes this agency the employer of record for a real
   ;; person, so it sits with the role that owns that liability -- and is
   ;; additionally routed to a human by `check` at every phase.
   :hiring-manager       #{:candidate/intake :worker/hire :worker/decline}
   :payroll-officer      #{:timesheet/approve}
   :dispute-officer      #{:dispute/request}
   :client-user          #{:report/query}})

(def hiring-ops
  "The two ops that decide a real person's employment relationship with
  this agency. Never auto-committable at any phase and never waivable by
  confidence: a human signs off on who is hired, and on who is turned
  down."
  #{:worker/hire :worker/decline})

(def private-fields
  "Fields that must NEVER appear in a proposal's value. A candidate record
  carries no personal identifiers at all (see `staffing.store`), and even
  the hired worker record has no field for a bank account or tax id
  because this actor never disburses payroll (ADR-2607111600 §1). This
  check is defense in depth against an advisor -- or a future schema
  change -- smuggling one in."
  #{:applicant-home-address :applicant-phone :applicant-email
    :applicant-date-of-birth :applicant-national-id
    :worker-bank-account :worker-tax-id})

(def referral-source-actors
  "Sibling actors whose human-carried referral drafts this agency accepts
  as a stated provenance, per ADR-2607202600's routing table (on-site-
  recurring lands here) and ADR-2607131000's handoff rule. `:direct-
  application` covers someone who applied to the agency itself.

  This validates the SHAPE of a provenance claim -- that it names an actor
  in this fleet's registry naming, or a direct application -- and nothing
  more. It deliberately does NOT verify that the draft exists on the other
  actor's side: confirming that would require the cross-actor invocation
  ADR-2607131000 forbids. An operator reconciling both ledgers by hand is
  the intended (and only) end-to-end check."
  #{"cloud-itonami-isic-6399" "cloud-itonami-isic-7810"
    "cloud-itonami-isic-8299" "cloud-itonami-isic-7820"})

(defn- referral-actor-name?
  "A fleet actor name we accept as a referral origin: one of the named
  staffing/matching actors, or any `cloud-itonami-isco-NNNN` occupation
  actor (the origin side of ADR-2607202600's human-required gap)."
  [n]
  (boolean (and (string? n)
                (or (contains? referral-source-actors n)
                    (re-matches #"cloud-itonami-isco-\d{4}" n)))))

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
  "The same eligibility discipline at BOTH points it matters: dispatching
  someone (place/extend), and becoming their employer of record in the
  first place (`:worker/hire`). Hiring reads the citation from the
  proposal's own `:value :eligibility` — the record about to be written —
  because at hire time there is no worker row to read it from yet."
  [{:keys [op]} proposal]
  (when (contains? #{:assignment/place :assignment/extend :worker/hire} op)
    (let [src (if (= :worker/hire op)
                (get-in proposal [:value :eligibility])
                (:source proposal))]
      (cond
        (or (nil? src) (not (facts/class-allowed? (:class src))))
        [{:rule :eligibility-gate
          :detail (str "worker の eligibility 出典が無いか許可されたクラスでない: " (pr-str src))}]

        (and (facts/operator-verified-class? (:class src)) (nil? (:verification-ref src)))
        [{:rule :eligibility-gate
          :detail "operator-verified-eligibility は :verification-ref を要求する"}]

        :else nil))))

(defn- unknown-worker-violations
  "A placement/extension against a worker id the SSoT does not know is a
  HARD rejection. Someone who has only been recorded as a candidate is
  deliberately NOT in `:workers` (see `staffing.store`), so this is also
  what stops a dispatch from reaching a person this agency has not hired."
  [{:keys [op]} proposal st]
  (when (contains? #{:assignment/place :assignment/extend} op)
    (let [worker-id (or (get-in proposal [:value :worker-id])
                        (:worker-id proposal))]
      (when (nil? (store/worker st worker-id))
        [{:rule :unknown-worker
          :detail (str "在籍しない worker への配属: " (pr-str worker-id)
                       (when (store/candidate st worker-id)
                         " (候補者として在籍。hire 前は配属不可)"))}]))))

(defn- provenance-violations
  "A candidate record must state HOW the person arrived, and the claim must
  be shape-valid (`referral-actor-name?`): a direct application, or a
  referral draft naming a fleet actor AND its draft id. An unattributed
  candidate would make the two-sided ledger reconciliation ADR-2607131000
  relies on impossible."
  [{:keys [op]} proposal]
  (when (= op :candidate/intake)
    (let [{:keys [kind from-actor draft-id]} (get-in proposal [:value :provenance])]
      (cond
        (nil? kind)
        [{:rule :provenance-gate :detail "候補者の出自(:provenance)が無い"}]

        (= :direct-application kind) nil

        (not= :referral-draft kind)
        [{:rule :provenance-gate :detail (str "未知の出自種別: " (pr-str kind))}]

        (not (referral-actor-name? from-actor))
        [{:rule :provenance-gate
          :detail (str "referral の紹介元 actor 名がフリートの命名に一致しない: "
                       (pr-str from-actor))}]

        (str/blank? (str draft-id))
        [{:rule :provenance-gate :detail "referral-draft に :draft-id が無い"}]))))

(defn- candidate-lifecycle-violations
  "candidate → hired | declined, exactly once. A duplicate intake would
  merge over an existing record; re-hiring would mint a second employment
  relationship for the same person; declining someone already hired would
  leave them dispatchable while marked declined."
  [{:keys [op] :as request} proposal st]
  (let [id (or (get-in proposal [:value :id])
               (get-in proposal [:value :candidate-id])
               (:subject request))
        c  (when id (store/candidate st id))]
    (case op
      :candidate/intake
      (cond
        (nil? id) [{:rule :candidate-lifecycle :detail "候補者に id が無い"}]
        (some? (store/worker st id))
        [{:rule :candidate-lifecycle :detail (str id " は既に在籍 worker")}]
        (some? c)
        [{:rule :candidate-lifecycle
          :detail (str id " の候補者記録は既にある (status=" (:status c) ")")}])

      (:worker/hire :worker/decline)
      (cond
        (nil? c) [{:rule :candidate-lifecycle :detail (str "候補者が存在しない: " (pr-str id))}]
        (not= :candidate (:status c))
        [{:rule :candidate-lifecycle
          :detail (str id " は既に " (:status c) " 済み — 再処理しない")}])

      nil)))

(defn- scope-violations
  [proposal]
  (let [ks  (set (keys (:value proposal)))
        bad (set/intersection ks private-fields)]
    (when (seq bad)
      [{:rule :scope-gate :detail (str "スキーマ外(個人情報/送金系)フィールドを含む: " (vec bad))}])))

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
          ;; `(or hours 0M)` used to default an unstated figure to zero, so a
          ;; timesheet claiming payment with NO hours produced total-hours 0,
          ;; the `(and (pos? total-hours) approved-amount)` branch was false,
          ;; and the minimum-wage check was skipped entirely -- the one gate
          ;; this op exists for. Unstated hours are now a violation, not a zero.
          total-hours (when (and (number? hours) (or (nil? overtime-hours)
                                                     (number? overtime-hours)))
                        (+ (double hours) (* (double (or overtime-hours 0M)) 1.5)))]
      (cond
        (nil? asg)
        [{:rule :wage-compliance-gate
          :detail (str "assignment " assignment-id " が登録されていない -- "
                       "適用される最低賃金を特定できない")}]

        (nil? floor)
        [{:rule :wage-compliance-gate
          :detail (str "jurisdiction " (:jurisdiction asg) " の wage-floor が未登録")}]

        (nil? total-hours)
        [{:rule :wage-compliance-gate
          :detail (str "労働時間が数値として申告されていない (hours=" (pr-str hours)
                       " overtime=" (pr-str overtime-hours) ") -- "
                       "実効時給を計算できないため最低賃金を検証できない")}]

        (not (number? approved-amount))
        [{:rule :wage-compliance-gate
          :detail (str "承認額が数値として申告されていない (" (pr-str approved-amount) ") -- "
                       "実効時給を計算できない")}]

        (not (pos? total-hours))
        [{:rule :wage-compliance-gate
          :detail "申告労働時間が 0 以下 -- 実効時給を計算できない"}]

        :else
        (let [effective (/ (double approved-amount) total-hours)]
          (when (< effective (double (:hourly-min floor)))
            [{:rule :wage-compliance-gate
              :detail (str "実効時給が最低賃金未満: effective=" effective
                           " < floor=" (:hourly-min floor))}]))))))

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
                              (unknown-worker-violations request proposal st)
                              (provenance-violations request proposal)
                              (candidate-lifecycle-violations request proposal st)
                              (scope-violations proposal)
                              (tenure-limit-violations request proposal st)
                              (wage-compliance-violations request proposal st)
                              (licensed-disclosure-violations request context proposal st)))
        conf        (:confidence proposal 0.0)
        low?        (< conf confidence-floor)
        hazardous?  (and (= :assignment/place (:op request)) (hazardous? proposal))
        dispute?    (= :dispute/request (:op request))
        hiring?     (contains? hiring-ops (:op request))
        hard?       (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not hazardous?) (not dispute?) (not hiring?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? hazardous? dispute? hiring?))
     :hazardous?   hazardous?
     :dispute?     dispute?
     :hiring?      hiring?}))

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
