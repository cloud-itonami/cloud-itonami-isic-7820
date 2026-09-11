(ns staffing.policy-worklaw-test
  "Proving slice for capability-library wiring (ADR-2607310300 D8):
  cloud-itonami-isic-7820 (temp staffing vertical) consuming
  kotoba-lang/worklaw (statutory working-time capability) — the complement
  of the existing wage-compliance gate.

  The advisor's claimed hours are NEVER trusted; worklaw recomputes from
  :worked-spans. Modeled on kintai's worklaw use (ADR-2607310400/0500) and
  isco-4313's labor recomputation pattern (deps.edn :local/root ->
  governor recomputes -> HARD HOLD on mismatch). This is the template the
  rest of the HR-capability cross-industry rollout copies."
  (:require [clojure.test :refer [deftest is testing]]
            [staffing.policy :as policy]
            [staffing.store :as store]))

(def ^:private day-ms (* 24 60 60 1000))
(def ^:private hour-ms (* 3600 1000))

(defn- span
  "A kotoba.shift-compatible worked span on calendar `day` (UTC), from
  start-hours to end-hours. No :worked/break-ms — the timecard a client
  returns records start/end, not lunch, and worklaw treats a missing break
  as :missing-break-data (unevaluated), not a violation (nil is not zero)."
  [day start-hours end-hours]
  (let [start (+ (* day day-ms) (* start-hours hour-ms))
        end   (+ (* day day-ms) (* end-hours   hour-ms))]
    {:worked/start start
     :worked/end   end
     :worked/ms    (- end start)}))

(defn- ts-approve-proposal
  [assignment-id spans approved & {:keys [period-from period-to hours]}]
  {:value     {:assignment-id   assignment-id
               :hours           (or hours 0M)
               :overtime-hours  0M
               :approved-amount approved
               :worked-spans    spans
               :period-from     period-from
               :period-to       period-to}
   :confidence 0.9})

(defn- check-ts
  [st assignment-id spans approved & {:as opts}]
  (policy/check {:op :timesheet/approve :subject "ts-test"}
                {:actor-role :payroll-officer}
                (apply ts-approve-proposal assignment-id spans approved
                       (mapcat identity opts))
                st))

(defn- has-rule? [v rule] (some #(= rule (:rule %)) (:violations v)))

(deftest gate-skips-when-no-spans
  (testing "a timesheet without :worked-spans is not checked by this gate
           (phased introduction). wage-compliance-gate still applies."
    (let [st (store/seed-db)
          v  (policy/check {:op :timesheet/approve :subject "t-100"}
                           {:actor-role :payroll-officer}
                           {:value {:assignment-id "a-100"
                                    :hours 160M :overtime-hours 0M
                                    :approved-amount 168000M}
                            :confidence 0.9}
                           st)]
      (is (not (has-rule? v :working-time-violation)))
      (is (not (has-rule? v :unchecked-working-law))))))

(deftest gate-passes-legal-jp-week
  (testing "a JPN assignment with daily 7h x 5 days (35h/week) is within
           労働基準法 第32条 (daily 8 / weekly 40)."
    (let [st    (store/seed-db)
          spans [(span 0 9 16) (span 1 9 16) (span 2 9 16)
                 (span 3 9 16) (span 4 9 16)]
          v     (check-ts st "a-100" spans 50000M
                          :hours 35M
                          :period-from 0 :period-to (* 7 day-ms))]
      (is (not (has-rule? v :working-time-violation)))
      (is (not (has-rule? v :unchecked-working-law))))))

(deftest gate-holds-daily-cap-breach-jp
  (testing "a JPN day over 8h statutory is a HARD HOLD — jp-daily-8
           (労働基準法 第32条第2項). A prohibition, not a premium the
           agency/client/worker can agree around."
    (let [st    (store/seed-db)
          spans [(span 0 9 19)]   ; 10h day
          v     (check-ts st "a-100" spans 15000M
                          :hours 10M
                          :period-from 0 :period-to day-ms)]
      (is (:hard? v))
      (is (has-rule? v :working-time-violation)))))

(deftest gate-holds-unsupported-jurisdiction
  (testing "a jurisdiction worklaw has no rules for is a HARD HOLD
           (:unchecked-working-law). Silence is never compliance — no human
           signature substitutes for a statute nobody encoded."
    (let [st (-> (store/seed-db)
                 (store/with-assignments
                   {"a-kor" {:id "a-kor" :worker-id "w-200" :client-id "c-100"
                             :jurisdiction :kor :role "x" :pay-rate 1200M
                             :start-date "2026-01-01" :end-date "2026-12-01"
                             :status :active}}))
          spans [(span 0 9 16)]
          v     (check-ts st "a-kor" spans 50000M
                          :hours 7M
                          :period-from 0 :period-to day-ms)]
      (is (:hard? v))
      (is (has-rule? v :unchecked-working-law)))))
