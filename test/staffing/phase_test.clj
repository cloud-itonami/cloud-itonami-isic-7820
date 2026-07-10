(ns staffing.phase-test
  "Phase 0→3 staged rollout through the OperationActor. The phase can only
  make the actor MORE conservative than the governor: hold writes that
  aren't enabled yet, force human approval before auto-commit is unlocked."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [staffing.store :as store]
            [staffing.operation :as op]))

(def coordinator {:actor-id "co-1" :actor-role :staffing-coordinator})
(def payroll     {:actor-id "pr-1" :actor-role :payroll-officer})
(def disputer    {:actor-id "do-1" :actor-role :dispute-officer})

(def clean-place
  {:op :assignment/place :subject "a-x" :id "a-x" :worker-id "w-100"
   :client-id "c-300" :jurisdiction :usa :role "picker" :pay-rate 9.00M
   :start-date "2026-07-01" :end-date "2026-10-01" :hazardous-duty? false})

(def clean-extend
  "a-100 already runs 2023-08-01 → 2026-06-01 (34 months). `new-end-date
  \"2026-07-01\"` (35 months) keeps w-200's cumulative JPN duration under
  the 36-month cap, so this fixture exercises the phase gate alone, not
  the tenure-limit-gate (see policy_contract_test.clj for that)."
  {:op :assignment/extend :subject "a-100" :assignment-id "a-100"
   :worker-id "w-200" :new-end-date "2026-07-01"})

(def clean-report
  {:op :report/query :subject "a-100" :assignment-id "a-100"})

(def dispute-req
  {:op :dispute/request :subject "a-100" :disputed-field :hours :claim 150M})

(defn- run [phase req ctx]
  (let [s (store/seed-db)
        actor (op/build s)]
    [s (g/run* actor {:request req :context (assoc ctx :phase phase)}
               {:thread-id (str "ph-" phase "-" (:op req))})]))

(deftest phase0-holds-all-writes
  (let [[s res] (run 0 clean-place coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) first :phase-reason)))
    (is (nil? (store/assignment s "a-x")) "SSoT untouched in phase 0")))

(deftest phase0-allows-governed-reads
  (testing "report/query is a read → phase 0 lets it through (governor still applies)"
    (let [[_ res] (run 0 clean-report {:actor-id "cl-1" :actor-role :client-user :tenant "tenant-c100"})]
      (is (= :commit (get-in res [:state :disposition]))))))

(deftest phase1-forces-approval-on-clean-placement
  (testing "a clean placement that auto-commits in phase 3 must go to a human in phase 1"
    (let [[_ res] (run 1 clean-place coordinator)]
      (is (= :interrupted (:status res)))
      (is (= :phase-approval (-> res :state :audit last :reason))))))

(deftest phase2-enables-extend-under-approval
  (let [[_ res] (run 2 clean-extend coordinator)]
    (is (= :interrupted (:status res)))
    (is (= :phase-approval (-> res :state :audit last :reason)))))

(deftest phase3-auto-commits-clean-placement
  (let [[s res] (run 3 clean-place coordinator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "a-x" (:id (store/assignment s "a-x"))))))

(deftest governor-hold-beats-phase
  (testing "a hard governor violation (missing eligibility) holds even in the most permissive phase"
    (let [[_ res] (run 3 {:op :assignment/place :subject "a-bad" :id "a-bad" :worker-id "w-400"
                          :client-id "c-300" :jurisdiction :usa :role "picker" :pay-rate 9.00M
                          :start-date "2026-07-01" :end-date "2026-10-01" :hazardous-duty? false}
                       coordinator)]
      (is (= :hold (get-in res [:state :disposition]))))))

(deftest dispute-request-never-auto-commits-at-any-phase
  (testing "a worker/client dispute never reaches :commit without an explicit human :approval"
    (doseq [ph [0 1 2 3]]
      (let [[_ res] (run ph dispute-req disputer)]
        (is (not= :commit (get-in res [:state :disposition]))
            (str "phase " ph " must not auto-commit a dispute"))))))

(deftest missing-phase-context-does-not-grant-max-autonomy
  (testing "omitting :phase from context still requires human approval on a clean placement"
    (let [s (store/seed-db)
          actor (op/build s)
          res (g/run* actor {:request clean-place :context coordinator} {:thread-id "mp"})]
      (is (not= :commit (get-in res [:state :disposition]))
          "a clean placement must not auto-commit when :phase is unset")
      (is (nil? (store/assignment s "a-x")) "SSoT untouched without explicit phase"))))
