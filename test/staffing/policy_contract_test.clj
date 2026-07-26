(ns staffing.policy-contract-test
  "The governor contract as executable tests — the analog of
  `cloud-itonami-isic-6311`'s policy_contract_test / robotaxi's
  safety_contract_test. The single invariant under test:

    TempStaffing-LLM never places/extends/approves/discloses/resolves a
    record the StaffingGovernor would reject, and every decision (commit
    OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [staffing.llm]
            [staffing.store :as store]
            [staffing.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def coordinator {:actor-id "co-1" :actor-role :staffing-coordinator})
(def payroll     {:actor-id "pr-1" :actor-role :payroll-officer})
(def disputer    {:actor-id "do-1" :actor-role :dispute-officer})
;; default-phase is 1 (assisted, no auto-commit -- see phase.cljc's
;; default-phase docstring). Tests exercising governor-clean auto-commit
;; opt into phase 3 explicitly, the same way phase_test.clj parameterizes
;; phase -- they are not testing "what happens with no :phase set" (that
;; is missing-phase-context-does-not-grant-max-autonomy's job).
(def coordinator-p3 (assoc coordinator :phase 3))
(def payroll-p3     (assoc payroll :phase 3))
(def disputer-p3    (assoc disputer :phase 3))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(deftest authorized-placement-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :assignment/place :subject "a-x" :id "a-x" :worker-id "w-100"
                   :client-id "c-300" :jurisdiction :usa :role "picker" :pay-rate 9.00M
                   :start-date "2026-07-01" :end-date "2026-10-01" :hazardous-duty? false}
                  coordinator-p3)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "a-x" (:id (store/assignment db "a-x"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))
    (is (= :commit (-> (store/ledger db) first :disposition)))))

(deftest unauthorized-role-is-held
  (testing "a :client-user role has no placement permission → HOLD, no write"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :assignment/place :subject "a-x" :id "a-x" :worker-id "w-100"
                     :client-id "c-300" :jurisdiction :usa :role "picker" :pay-rate 9.00M
                     :start-date "2026-07-01" :end-date "2026-10-01" :hazardous-duty? false}
                    {:actor-id "cl-1" :actor-role :client-user})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (nil? (store/assignment db "a-x")) "SSoT unchanged")
      (is (= [:rbac] (-> (store/ledger db) first :basis))))))

(deftest missing-eligibility-placement-is-held
  (testing "a worker with no eligibility on file → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :assignment/place :subject "a-bad" :id "a-bad" :worker-id "w-400"
                     :client-id "c-300" :jurisdiction :usa :role "picker" :pay-rate 9.00M
                     :start-date "2026-07-01" :end-date "2026-10-01" :hazardous-duty? false}
                    coordinator-p3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:eligibility-gate} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assignment db "a-bad"))))))

(deftest ignore-eligibility-flag-is-held
  (testing "a placement proposal that omits the worker's on-file eligibility citation → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t3b"
                    {:op :assignment/place :subject "a-y" :id "a-y" :worker-id "w-100"
                     :client-id "c-300" :jurisdiction :usa :role "picker" :pay-rate 9.00M
                     :start-date "2026-07-01" :end-date "2026-10-01" :hazardous-duty? false
                     :ignore-eligibility? true}
                    coordinator-p3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:eligibility-gate} (-> (store/ledger db) first :basis))))))

(deftest tenure-cap-breach-on-extend-is-held
  (testing "extending past JPN's 労働者派遣法 36-month cap → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t4"
                    {:op :assignment/extend :subject "a-100" :assignment-id "a-100"
                     :worker-id "w-200" :new-end-date "2027-06-01"}
                    coordinator-p3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:tenure-limit-gate} (-> (store/ledger db) first :basis)))
      (is (= "2026-06-01" (:end-date (store/assignment db "a-100"))) "no extension written"))))

(deftest tenure-cap-not-enforced-for-usa
  (testing "USA has no tenure-cap entry -- a long extension is not rejected on tenure grounds"
    (let [[db actor] (fresh)
          _ (exec-op actor "seed-usa"
                     {:op :assignment/place :subject "a-usa" :id "a-usa" :worker-id "w-100"
                      :client-id "c-300" :jurisdiction :usa :role "picker" :pay-rate 9.00M
                      :start-date "2020-01-01" :end-date "2026-10-01" :hazardous-duty? false}
                     coordinator-p3)
          res (exec-op actor "t4b"
                       {:op :assignment/extend :subject "a-usa" :assignment-id "a-usa"
                        :worker-id "w-100" :new-end-date "2032-01-01"}
                       coordinator-p3)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (not (some #{:tenure-limit-gate} (-> (store/ledger db) last :basis)))))))

(deftest wage-below-floor-is-held
  (testing "a timesheet approval whose effective hourly rate is under the jurisdiction's wage floor → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :timesheet/approve :subject "a-100" :id "t-x" :assignment-id "a-100"
                     :hours 100M :overtime-hours 100M :miscalc? true}
                    payroll-p3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:wage-compliance-gate} (-> (store/ledger db) first :basis))))))

(deftest correct-wage-calc-commits-directly
  (let [[db actor] (fresh)
        res (exec-op actor "t5b"
                  {:op :timesheet/approve :subject "a-100" :id "t-x" :assignment-id "a-100"
                   :hours 100M :overtime-hours 100M}
                  payroll-p3)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 300000M (:approved-amount (store/timesheet db "t-x"))))))

(deftest unregistered-wage-floor-jurisdiction-is-held
  (testing "approving a timesheet in a jurisdiction with no operator-maintained wage floor → HOLD"
    (let [[db actor] (fresh)
          ;; w-100 has no prior assignments anywhere, so a fresh 1-month
          ;; placement stays well under GBR's 3-month tenure cap -- this
          ;; isolates the wage-floor-lookup failure from the tenure check.
          _ (exec-op actor "seed-gbr"
                     {:op :assignment/place :subject "a-gbr" :id "a-gbr" :worker-id "w-100"
                      :client-id "c-200" :jurisdiction :gbr :role "picker" :pay-rate 12.00M
                      :start-date "2026-07-01" :end-date "2026-08-01" :hazardous-duty? false}
                     coordinator-p3)
          res (exec-op actor "t5c"
                       {:op :timesheet/approve :subject "a-gbr" :id "t-gbr" :assignment-id "a-gbr"
                        :hours 40M :overtime-hours 0M}
                       payroll-p3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:wage-compliance-gate} (-> (store/ledger db) last :basis))))))

(deftest uncontracted-report-is-held
  (testing "a report query from a tenant with no registered contract → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :report/query :subject "a-100" :assignment-id "a-100"}
                    {:actor-id "cl-2" :actor-role :client-user :tenant "tenant-ghost"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:licensed-disclosure} (-> (store/ledger db) first :basis))))))

(deftest over-tier-report-is-held
  (testing "a report query pulling columns beyond the contract's tier → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t7"
                    {:op :report/query :subject "a-100" :assignment-id "a-100" :greedy? true}
                    {:actor-id "cl-1" :actor-role :client-user :tenant "tenant-c100"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:licensed-disclosure} (-> (store/ledger db) first :basis))))))

(deftest clean-report-within-tier-commits-directly
  (let [[_db actor] (fresh)
        res (exec-op actor "t7b"
                  {:op :report/query :subject "a-100" :assignment-id "a-100"}
                  {:actor-id "cl-1" :actor-role :client-user :tenant "tenant-c100"})]
    (is (= :commit (get-in res [:state :disposition])))))

(deftest hazardous-placement-escalates-then-human-decides
  (testing "an otherwise-clean placement flagged hazardous-duty interrupts for human approval"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t8"
                   {:op :assignment/place :subject "a-hz" :id "a-hz" :worker-id "w-300"
                    :client-id "c-200" :jurisdiction :deu :role "crane-operator" :pay-rate 20.00M
                    :start-date "2026-07-01" :end-date "2026-09-01" :hazardous-duty? true}
                   coordinator-p3)]
      (is (= :interrupted (:status r1)) "pauses for human approval")
      (is (= :hazardous-duty-assignment (-> r1 :state :audit last :reason)))
      (testing "approve → commit"
        (let [r2 (g/run* actor {:approval {:status :approved :by "coordinator-1"}}
                         {:thread-id "t8" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= "a-hz" (:id (store/assignment db "a-hz"))))
          (is (= :commit (-> (store/ledger db) last :disposition)))))))
  (testing "reject → hold"
    (let [[db actor] (fresh)
          _  (exec-op actor "t9"
                  {:op :assignment/place :subject "a-hz2" :id "a-hz2" :worker-id "w-300"
                   :client-id "c-200" :jurisdiction :deu :role "crane-operator" :pay-rate 20.00M
                   :start-date "2026-07-01" :end-date "2026-09-01" :hazardous-duty? true}
                  coordinator-p3)
          r2 (g/run* actor {:approval {:status :rejected :by "coordinator-1"}}
                     {:thread-id "t9" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (nil? (store/assignment db "a-hz2"))))))

(deftest dispute-request-always-escalates-regardless-of-confidence
  (testing "a worker/client dispute always reaches a human, never auto-resolves"
    (let [[db actor] (fresh)
          before (store/assignment db "a-100")
          r1 (exec-op actor "t10"
                   {:op :dispute/request :subject "a-100" :disputed-field :role :claim "senior-line-worker"}
                   disputer-p3)]
      (is (= :interrupted (:status r1)))
      (is (= :worker-client-dispute (-> r1 :state :audit last :reason)))
      (testing "approve → commit applies the dispute resolution"
        (let [r2 (g/run* actor {:approval {:status :approved :by "coordinator-1"}}
                         {:thread-id "t10" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= "senior-line-worker" (:role (store/assignment db "a-100"))))))
      (testing "a second, rejected dispute leaves the assignment unchanged"
        (let [[db2 actor2] (fresh)
              _  (exec-op actor2 "t11"
                      {:op :dispute/request :subject "a-100" :disputed-field :role :claim "senior-line-worker"}
                      disputer-p3)
              r3 (g/run* actor2 {:approval {:status :rejected :by "coordinator-1"}}
                        {:thread-id "t11" :resume? true})]
          (is (= :hold (get-in r3 [:state :disposition])))
          (is (= (:role before) (:role (store/assignment db2 "a-100")))))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations → N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :assignment/place :subject "a-x" :id "a-x" :worker-id "w-100"
                          :client-id "c-300" :jurisdiction :usa :role "picker" :pay-rate 9.00M
                          :start-date "2026-07-01" :end-date "2026-10-01" :hazardous-duty? false}
               coordinator-p3)
      (exec-op actor "b" {:op :assignment/place :subject "a-bad" :id "a-bad" :worker-id "w-400"
                          :client-id "c-300" :jurisdiction :usa :role "picker" :pay-rate 9.00M
                          :start-date "2026-07-01" :end-date "2026-10-01" :hazardous-duty? false}
               coordinator-p3)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))

;; ───────────── hiring intake: candidate → hired | declined ─────────────
;; The invariant: this agency can PREPARE, GOVERN and RECORD who it employs,
;; but never decides it. Every hire/decline reaches a human at every phase,
;; and a candidate is never dispatchable.

(def hiring   {:actor-id "hm-1" :actor-role :hiring-manager})
(def hiring-p3 (assoc hiring :phase 3))

(defn- intake-request [overrides]
  (merge {:op :candidate/intake :subject "cd-900" :candidate-id "cd-900"
          :handle "test-candidate"
          :provenance {:kind :referral-draft
                       :from-actor "cloud-itonami-isco-8332"
                       :draft-id "draft-test-1"}
          :claimed-skills #{:on-site-install}
          :available-from "2026-09-01"
          :location-scope :per-engagement
          :contact-ref "gh-issue:example/repo#1"}
         overrides))

(deftest referral-draft-intake-records-a-candidate-not-a-worker
  (let [[db actor] (fresh)
        res (exec-op actor "h1" (intake-request {}) coordinator-p3)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :candidate (:status (store/candidate db "cd-900"))))
    (is (= "cloud-itonami-isco-8332" (get-in (store/candidate db "cd-900") [:provenance :from-actor])))
    (is (nil? (store/worker db "cd-900"))
        "recording a referral must not employ anyone")))

(deftest direct-application-intake-needs-no-referral-actor
  (let [[db actor] (fresh)
        res (exec-op actor "h2" (intake-request {:candidate-id "cd-901" :subject "cd-901"
                                                 :provenance {:kind :direct-application}})
                     coordinator-p3)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :direct-application (get-in (store/candidate db "cd-901") [:provenance :kind])))))

(deftest unattributed-or-bogus-provenance-is-a-hard-hold
  (let [[db actor] (fresh)]
    (doseq [[label prov] [["no provenance at all" nil]
                          ["unknown kind" {:kind :vibes}]
                          ["referral naming a non-fleet actor" {:kind :referral-draft
                                                                :from-actor "some-random-agency"
                                                                :draft-id "d1"}]
                          ["referral with no draft id" {:kind :referral-draft
                                                        :from-actor "cloud-itonami-isic-8299"}]]]
      (testing label
        (let [id (str "cd-902-" (hash label))
              res (exec-op actor (str "h3-" (hash label))
                           (intake-request {:candidate-id id :subject id :provenance prov})
                           coordinator-p3)]
          (is (= :hold (get-in res [:state :disposition])))
          (is (some #(= :provenance-gate (:rule %)) (get-in res [:state :verdict :violations])))
          (is (nil? (store/candidate db id))))))))

(deftest hiring-always-reaches-a-human-even-at-phase-3
  (let [[db actor] (fresh)
        res (exec-op actor "h4"
                     {:op :worker/hire :subject "cd-100" :candidate-id "cd-100"
                      :name "採用された人(テスト)"
                      :eligibility {:class :operator-verified-eligibility
                                    :ref "jpn-zairyu:test" :verification-ref "ver-test"}}
                     hiring-p3)]
    (is (= :interrupted (:status res)) "employment decisions pause for a human")
    (is (nil? (store/worker db "cd-100")) "nobody is employed before sign-off")
    (let [resumed (g/run* actor {:approval {:status :approved :by "hm-1"}}
                          {:thread-id "h4" :resume? true})]
      (is (= :commit (get-in resumed [:state :disposition])))
      (is (= "採用された人(テスト)" (:name (store/worker db "cd-100"))))
      (is (= :hired (:status (store/candidate db "cd-100")))
          "the candidate record is retained, marked hired"))))

(deftest hire-without-a-valid-eligibility-citation-is-a-hard-hold
  (let [[db actor] (fresh)]
    (doseq [[label elig] [["missing" nil]
                          ["non-catalog class" {:class :trust-me :ref "x"}]
                          ["operator-verified without verification-ref"
                           {:class :operator-verified-eligibility :ref "x"}]]]
      (testing label
        (let [res (exec-op actor (str "h5-" (hash label))
                           {:op :worker/hire :subject "cd-100" :candidate-id "cd-100"
                            :name "n" :eligibility elig}
                           hiring-p3)]
          (is (= :hold (get-in res [:state :disposition]))
              "held BEFORE any human is asked -- not escalated for approval")
          (is (some #(= :eligibility-gate (:rule %)) (get-in res [:state :verdict :violations])))
          (is (nil? (store/worker db "cd-100"))))))))

(deftest decline-reaches-a-human-and-keeps-the-record
  (let [[db actor] (fresh)
        res (exec-op actor "h6" {:op :worker/decline :subject "cd-100"
                                 :candidate-id "cd-100" :reason :location-mismatch}
                     hiring-p3)]
    (is (= :interrupted (:status res)))
    (let [resumed (g/run* actor {:approval {:status :approved :by "hm-1"}}
                          {:thread-id "h6" :resume? true})]
      (is (= :commit (get-in resumed [:state :disposition])))
      (is (= :declined (:status (store/candidate db "cd-100"))))
      (is (= :location-mismatch (:decline-reason (store/candidate db "cd-100"))))
      (is (nil? (store/worker db "cd-100"))))))

(deftest coordinator-cannot-hire-and-duplicate-intake-is-held
  (let [[db actor] (fresh)]
    (testing "only the hiring manager may hire"
      (let [res (exec-op actor "h7" {:op :worker/hire :subject "cd-100" :candidate-id "cd-100"
                                     :name "n" :eligibility {:class :i9-eligibility-verification
                                                             :ref "i9:test"}}
                         coordinator-p3)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #(= :rbac (:rule %)) (get-in res [:state :verdict :violations])))))
    (testing "a candidate already on file cannot be re-recorded"
      (let [res (exec-op actor "h8" (intake-request {:candidate-id "cd-100" :subject "cd-100"})
                         coordinator-p3)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #(= :candidate-lifecycle (:rule %)) (get-in res [:state :verdict :violations])))))
    (testing "an employed worker's id cannot be re-recorded as a candidate"
      (let [res (exec-op actor "h9" (intake-request {:candidate-id "w-100" :subject "w-100"})
                         coordinator-p3)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (= "田中 花子(デモ)" (:name (store/worker db "w-100"))) "existing worker untouched")))))

(deftest placing-a-candidate-who-was-never-hired-is-a-hard-hold
  (let [[db actor] (fresh)
        res (exec-op actor "h10"
                     {:op :assignment/place :subject "a-cd" :id "a-cd" :worker-id "cd-100"
                      :client-id "c-300" :jurisdiction :usa :role "picker" :pay-rate 9.00M
                      :start-date "2026-07-01" :end-date "2026-10-01" :hazardous-duty? false}
                     coordinator-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #(= :unknown-worker (:rule %)) (get-in res [:state :verdict :violations])))
    (is (nil? (store/assignment db "a-cd")))))

(deftest applicant-pii-or-payment-fields-are-a-hard-hold
  (let [pii-advisor (reify staffing.llm/Advisor
                      (-advise [_ _ _]
                        {:summary "x" :rationale "y" :cites [] :source nil
                         :effect :candidate-upsert
                         :value {:id "cd-903" :handle "h"
                                 :provenance {:kind :direct-application}
                                 :applicant-phone "090-0000-0000"
                                 :worker-bank-account "1234567"}
                         :confidence 0.95}))
        db (store/seed-db)
        actor (op/build db {:advisor pii-advisor})
        res (exec-op actor "h11" (intake-request {:candidate-id "cd-903" :subject "cd-903"
                                                  :provenance {:kind :direct-application}})
                     coordinator-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #(= :scope-gate (:rule %)) (get-in res [:state :verdict :violations])))
    (is (nil? (store/candidate db "cd-903")))))
