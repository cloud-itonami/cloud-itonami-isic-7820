(ns staffing.sim
  "Demo runner: push seven representative operations through one
  OperationActor and watch the StaffingGovernor + approval workflow earn
  the TempStaffing-LLM the right to place, extend, approve or disclose.

    op1  クリーンな新規配置(USA、tenure cap 対象外)          → commit(phase 3)
    op2  eligibility 未登録の worker への配置                → eligibility-gate REJECT → hold
    op3  延長が JPN 派遣法40条の2 の36ヶ月上限を超過          → tenure-limit-gate REJECT → hold
    op4  タイムシート承認の実効時給が最低賃金未満(計算誤り)  → wage-compliance-gate REJECT → hold
    op5  レポートクエリが tier/basic 契約なのに worker-id/pay-rate まで要求 → licensed-disclosure REJECT → hold
    op6  hazardous-duty 銘柄への配置(出典・cap は正常でも人間承認) → 人間承認へ escalate → approve → commit
    op7  異議申立て(どの phase でも常に人間レビュー)        → escalate → approve → commit

  Run: clojure -M:dev:run"
  (:require [langgraph.graph :as g]
            [staffing.store :as store]
            [staffing.operation :as op]
            [staffing.facts :as facts]
            [staffing.report :as report]))

(defn- line [& xs] (println (apply str xs)))

(defn- run-op!
  "Run one operation on its own thread-id. If it interrupts for human
  approval, a staffing coordinator 'approves' and we resume."
  [actor thread-id request context approve?]
  (let [res (g/run* actor {:request request :context context} {:thread-id thread-id})]
    (if (= :interrupted (:status res))
      (do (line "   ⏸  人間レビュー待ち (reason: "
                (-> res :state :audit last :reason) ")")
          (let [res2 (g/run* actor
                             {:approval {:status (if approve? :approved :rejected)
                                         :by "coordinator-1"}}
                             {:thread-id thread-id :resume? true})]
            (line "   ▶  " (if approve? "承認 → " "却下 → ") "disposition = "
                  (get-in res2 [:state :disposition]))
            res2))
      (do (line "   → disposition = " (get-in res [:state :disposition])
                "  (confidence " (get-in res [:state :verdict :confidence]) ")")
          res))))

(defn -main [& _]
  (let [db    (store/seed-db)
        actor (op/build db)
        coordinator {:actor-id "co-1" :actor-role :staffing-coordinator :phase 3}
        payroll     {:actor-id "pr-1" :actor-role :payroll-officer :phase 3}
        ;; phase 3 (max autonomy) deliberately, to demonstrate that
        ;; :dispute/request escalates even at the most permissive phase --
        ;; it is never a member of any phase's :auto set.
        disputer    {:actor-id "do-1" :actor-role :dispute-officer :phase 3}]

    (line "── R0 statutory カバレッジ(正直な現状) ──")
    (line (pr-str (facts/coverage)))

    (line "\n── OperationActor (TempStaffing-LLM sealed; StaffingGovernor active) ──")

    (line "\nop1  クリーンな新規配置(USA、tenure cap 対象外)")
    (run-op! actor "op1"
             {:op :assignment/place :subject "a-usa1" :id "a-usa1" :worker-id "w-100"
              :client-id "c-300" :jurisdiction :usa :role "picker" :pay-rate 9.00M
              :start-date "2026-07-01" :end-date "2026-10-01" :hazardous-duty? false}
             coordinator true)

    (line "\nop2  eligibility 未登録の worker への配置")
    (run-op! actor "op2"
             {:op :assignment/place :subject "a-bad1" :id "a-bad1" :worker-id "w-400"
              :client-id "c-300" :jurisdiction :usa :role "picker" :pay-rate 9.00M
              :start-date "2026-07-01" :end-date "2026-10-01" :hazardous-duty? false}
             coordinator true)

    (line "\nop3  延長が JPN 派遣法40条の2 の36ヶ月上限を超過")
    (run-op! actor "op3"
             {:op :assignment/extend :subject "a-100" :assignment-id "a-100"
              :worker-id "w-200" :new-end-date "2027-06-01"}
             coordinator true)

    (line "\nop4  タイムシート承認の実効時給が最低賃金未満(残業を割増無しの計算誤り)")
    (run-op! actor "op4"
             {:op :timesheet/approve :subject "a-100" :id "t-100" :assignment-id "a-100"
              :hours 100M :overtime-hours 100M :miscalc? true}
             payroll true)

    (line "\nop5  レポートクエリ(tier/basic 契約なのに worker-id/pay-rate まで要求)")
    (run-op! actor "op5"
             {:op :report/query :subject "a-100" :assignment-id "a-100" :greedy? true}
             {:actor-id "cl-1" :actor-role :client-user :tenant "tenant-c100"} true)

    (line "\nop6  hazardous-duty 配置(出典・cap は正常でも人間承認)")
    (run-op! actor "op6"
             {:op :assignment/place :subject "a-hz1" :id "a-hz1" :worker-id "w-300"
              :client-id "c-200" :jurisdiction :deu :role "crane-operator" :pay-rate 20.00M
              :start-date "2026-07-01" :end-date "2026-09-01" :hazardous-duty? true}
             coordinator true)

    (line "\nop7  異議申立て — worker がタイムシートの時間数に異議(どの phase でも常に人間レビュー)")
    (run-op! actor "op7"
             {:op :dispute/request :subject "a-100" :disputed-field :hours :claim 150M}
             disputer true)

    (line "\n── 開示(governor が承認した tier/basic 列のみ) ──")
    (line (pr-str (report/render-report db "a-100" [:assignment-id :role :period :hours :approved-amount])))

    (line "\n── 監査台帳 (append-only; 誰が・何を・どの eligibility/tenure/wage 基準で行ったか) ──")
    (doseq [f (store/ledger db)]
      (line "  " (store/ledger-line f)))

    (line "\ndone.")))
