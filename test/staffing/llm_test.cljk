(ns staffing.llm-test
  "TempStaffing-LLM proposal generation, unit-level (no governor/actor
  involved — that integration is covered by policy_contract_test)."
  (:require [clojure.test :refer [deftest is testing]]
            [staffing.store :as store]
            [staffing.llm :as llm]))

(deftest place-proposal-carries-worker-eligibility-as-source
  (let [db (store/seed-db)
        p (llm/infer db {:op :assignment/place :subject "a-x" :id "a-x" :worker-id "w-100"
                         :client-id "c-300" :jurisdiction :usa :role "picker" :pay-rate 9.00M
                         :start-date "2026-07-01" :end-date "2026-10-01" :hazardous-duty? false})]
    (is (= :assignment-upsert (:effect p)))
    (is (= {:class :i9-eligibility-verification :ref "i9:demo-w100"} (:source p)))
    (is (>= (:confidence p) 0.85))))

(deftest ignore-eligibility-proposal-carries-nil-source
  (testing "the LLM layer does not filter -- that is the governor's job; this only proves the injected failure mode actually reaches the proposal"
    (let [db (store/seed-db)
          p (llm/infer db {:op :assignment/place :subject "a-x" :id "a-x" :worker-id "w-100"
                           :client-id "c-300" :jurisdiction :usa :role "picker" :pay-rate 9.00M
                           :start-date "2026-07-01" :end-date "2026-10-01" :hazardous-duty? false
                           :ignore-eligibility? true})]
      (is (nil? (:source p)))
      (is (>= (:confidence p) 0.85) "still high-confidence -- proves eligibility-gate cannot rely on confidence as a proxy"))))

(deftest extend-proposal-preserves-other-assignment-fields
  (let [db (store/seed-db)
        p (llm/infer db {:op :assignment/extend :subject "a-100" :assignment-id "a-100"
                         :worker-id "w-200" :new-end-date "2027-06-01"})]
    (is (= "2027-06-01" (get-in p [:value :end-date])))
    (is (= "w-200" (get-in p [:value :worker-id])))
    (is (= :jpn (get-in p [:value :jurisdiction])))))

(deftest approve-proposal-correct-calc-matches-base-rate
  (let [db (store/seed-db)
        p (llm/infer db {:op :timesheet/approve :subject "a-100" :id "t-x" :assignment-id "a-100"
                         :hours 100M :overtime-hours 100M})]
    (is (= 300000M (get-in p [:value :approved-amount])))))

(deftest approve-proposal-miscalc-underpays-overtime
  (testing "miscalc? pays overtime at straight time instead of 1.5x -- a lower approved amount for the same hours"
    (let [db (store/seed-db)
          correct (llm/infer db {:op :timesheet/approve :subject "a-100" :id "t-x" :assignment-id "a-100"
                                 :hours 100M :overtime-hours 100M})
          miscalced (llm/infer db {:op :timesheet/approve :subject "a-100" :id "t-x" :assignment-id "a-100"
                                   :hours 100M :overtime-hours 100M :miscalc? true})]
      (is (< (get-in miscalced [:value :approved-amount]) (get-in correct [:value :approved-amount]))))))

(deftest report-proposal-greedy-adds-extra-columns
  (let [db (store/seed-db)
        clean (llm/infer db {:op :report/query :subject "a-100" :assignment-id "a-100"})
        greedy (llm/infer db {:op :report/query :subject "a-100" :assignment-id "a-100" :greedy? true})]
    (is (< (count (:columns clean)) (count (:columns greedy))))
    (is (some #{:worker-id :pay-rate} (:columns greedy)))))

(deftest dispute-proposal-never-marks-high-confidence
  (let [db (store/seed-db)
        p (llm/infer db {:op :dispute/request :subject "a-100" :disputed-field :hours :claim 150M})]
    (is (= :dispute-apply (:effect p)))
    (is (< (:confidence p) 0.9) "disputes are claims pending human verification, never auto-confident")))
