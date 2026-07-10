(ns staffing.llm
  "TempStaffing-LLM client — the *contained intelligence node*.

  It drafts a new assignment placement or extension (citing the worker's
  on-file eligibility verification), computes a proposed timesheet
  approval amount from hours worked, and proposes client-report column
  sets. CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields/source it cited), never a
  committed or disclosed record. Every output is censored downstream by
  `staffing.policy` (the StaffingGovernor) before anything touches the SSoT
  or is disclosed to a client.

  Like `cloud-itonami-isic-6311`'s MarketData-LLM, this is a deterministic
  mock so the actor graph runs offline and the governor contract is
  exercised end-to-end. In production this calls a real LLM (kotoba-llm)
  with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why — SCANNED by the eligibility gate
     :cites      [kw|str ..]    ; fields/attrs the LLM used
     :source     {:class kw :ref str :verification-ref str?}|nil ; SCANNED by eligibility-gate
     :effect     kw             ; how a commit would mutate the SSoT
     :value      map|nil        ; the assignment/timesheet/dispute patch
     :columns    [kw ..]|nil    ; proposed report column set
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]
            [staffing.store :as store]))

(defn- propose-place
  "New assignment placement — cites the worker's on-file eligibility
  verification as `:source`. `:ignore-eligibility?` injects the failure
  mode we must defend against: proposing a placement without citing the
  worker's eligibility at all (a corner-cutting LLM) — the
  StaffingGovernor's eligibility-gate must reject this outright."
  [db {:keys [id worker-id client-id jurisdiction role pay-rate start-date end-date
              hazardous-duty? ignore-eligibility?]}]
  (let [w (store/worker db worker-id)
        src (when-not ignore-eligibility? (:eligibility w))]
    {:summary   (str "assignment place: " worker-id " → " client-id " (" role ")")
     :rationale "worker の on-file eligibility を引用した新規配置提案。"
     :cites     [:worker-id :client-id :role :start-date :end-date]
     :source    src
     :effect    :assignment-upsert
     :value     {:id id :worker-id worker-id :client-id client-id :jurisdiction jurisdiction
                 :role role :pay-rate pay-rate :start-date start-date :end-date end-date
                 :hazardous-duty? hazardous-duty? :status :active}
     :confidence 0.9}))

(defn- propose-extend
  "Assignment extension — cites the worker's on-file eligibility the same
  way `propose-place` does (eligibility must still be valid to extend)."
  [db {:keys [assignment-id worker-id new-end-date ignore-eligibility?]}]
  (let [w (store/worker db worker-id)
        src (when-not ignore-eligibility? (:eligibility w))
        asg (store/assignment db assignment-id)]
    {:summary   (str "assignment extend: " assignment-id " → " new-end-date)
     :rationale "worker の on-file eligibility を引用した延長提案。"
     :cites     [:end-date]
     :source    src
     :effect    :assignment-upsert
     :value     (assoc asg :end-date new-end-date)
     :confidence 0.9}))

(defn- propose-approve
  "Timesheet approval — computes the proposed payroll amount from the
  assignment's on-file pay-rate and the submitted hours. `:miscalc?`
  injects a realistic payroll bug (paying overtime hours at the straight
  rate instead of the 1.5x premium) so the wage-compliance-gate has
  something real to catch — the effective hourly rate this produces drops
  below the jurisdiction's wage floor whenever overtime is a meaningful
  share of total hours, exactly the shape a real rate-engine defect takes."
  [db {:keys [id assignment-id hours overtime-hours miscalc?]}]
  (let [asg (store/assignment db assignment-id)
        rate (:pay-rate asg)
        amount (if miscalc?
                 (* rate (+ hours overtime-hours)) ;; overtime paid at straight time, not 1.5x
                 (+ (* rate hours) (* rate overtime-hours 1.5M)))]
    {:summary   (str "timesheet approve: " id " (" hours "h + " overtime-hours "h OT)")
     :rationale "assignment 記載の pay-rate と提出時間からの機械計算。"
     :cites     [:hours :overtime-hours :pay-rate]
     :source    nil
     :effect    :timesheet-upsert
     :value     {:id id :assignment-id assignment-id :hours hours :overtime-hours overtime-hours
                 :approved-amount amount :status :approved}
     :confidence 0.95}))

(defn- propose-report
  "Client-report column-set proposal. `:greedy?` injects over-disclosure
  (pulls worker-id/pay-rate/eligibility columns beyond a basic-tier
  contract) — the StaffingGovernor's licensed-disclosure gate must reject
  the excess columns."
  [_db {:keys [assignment-id greedy?]}]
  (let [base [:assignment-id :role :period :hours :approved-amount]
        greedy-extra [:worker-id :pay-rate :eligibility-status]]
    {:summary   (str "開示列提案: " assignment-id)
     :rationale (if greedy? "分析に有用そうな列を広めに含めた。" "契約 tier に必要な最小列のみ。")
     :cites     base
     :source    nil
     :effect    :report-serve
     :columns   (if greedy? (into base greedy-extra) base)
     :confidence 0.9}))

(defn- propose-dispute
  "Worker/client dispute resolution draft. This NEVER auto-applies —
  `staffing.policy` and `staffing.phase` both structurally force every
  `:dispute/request` to human review, independent of confidence."
  [_db {:keys [disputed-field claim]}]
  {:summary   (str "assignment の " disputed-field " について異議申立てへの解決案ドラフト")
   :rationale (str "申立て内容: " claim "。裏取りは人間レビューで行う。")
   :cites     [disputed-field]
   :source    nil
   :effect    :dispute-apply
   :value     {:patch {disputed-field claim}}
   :confidence 0.5})

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :assignment/place    (propose-place db request)
    :assignment/extend   (propose-extend db request)
    :timesheet/approve   (propose-approve db request)
    :report/query        (propose-report db request)
    :dispute/request     (propose-dispute db request)
    {:summary "未対応の操作" :rationale (str op) :cites [] :source nil
     :effect :noop :confidence 0.0}))

;; ───────────────────────── Advisor protocol ─────────────────────────
;; The advisor is injected into the OperationActor, so the contained
;; intelligence node is a swap: a deterministic mock for dev/tests, or a real
;; LLM in production. Either way its output is a PROPOSAL the
;; StaffingGovernor still censors — the single invariant never depends on
;; which advisor ran.

(defprotocol Advisor
  (-advise [advisor store request] "store + request → proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは一時労働者派遣(temp staffing)の配置・延長・タイムシート承認・"
       "レポート提案アドバイザーです。与えられた事実のみに基づき、提案を1つ"
       "だけ EDN マップで返します。説明や前置きは一切書かず、EDN だけを"
       "出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) :source({:class .. :ref ..}か nil) "
       ":effect(:assignment-upsert|:timesheet-upsert|:report-serve|:dispute-apply) "
       ":value(該当マップ) :confidence(0..1)。\n"
       "重要: worker の eligibility 出典を伴わない配置・延長は絶対に提案しては"
       "いけません。累計継続期間が法定上限を超えるか、時給が最低賃金を"
       "満たすかの判定はあなたの責務ではありません(governor が判定します)。"))

(defn- facts-for [st {:keys [op worker-id assignment-id]}]
  (case op
    :assignment/place  {:worker (store/worker st worker-id)}
    :assignment/extend {:worker (store/worker st worker-id) :assignment (store/assignment st assignment-id)}
    {:assignment (store/assignment st assignment-id)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure yields
  a safe low-confidence noop so the StaffingGovernor escalates/holds — an
  LLM hiccup can never auto-commit or auto-serve."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :source nil :effect :noop :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference). Pass
  `model/anthropic-model`, an OpenAI-compatible model (Ollama/vLLM/kotoba), or
  `model/mock-model` for offline tests. `gen-opts` is forwarded to -generate."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record — the LLM's interpretable rationale is a
  key asset (dispute appeals, audits). Persisted to the :audit channel."
  [request proposal]
  {:t          :tempstaffingllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :source     (:source proposal)
   :confidence (:confidence proposal)})
