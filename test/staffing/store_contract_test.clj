(ns staffing.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and the
  Datomic-backed (langchain.db) store satisfy the same contract is what
  makes 'swap the SSoT for Datomic' a configuration change, not a rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [staffing.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "田中 花子(デモ)" (:name (store/worker s "w-100"))))
      (is (= {:class :i9-eligibility-verification :ref "i9:demo-w100"}
             (:eligibility (store/worker s "w-100"))))
      (is (nil? (:eligibility (store/worker s "w-400"))))
      (is (= "デモ製造株式会社" (:name (store/client s "c-100"))))
      (is (= :jpn (:jurisdiction (store/assignment s "a-100"))))
      (is (= 1 (count (store/assignments-of-worker s "w-200"))))
      (is (= 160M (:hours (store/timesheet s "t-100"))))
      (is (= 1 (count (store/timesheets-of-assignment s "a-100"))))
      (is (= 1050M (:hourly-min (store/wage-floor s :jpn)))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "assignment upsert replaces the assignment"
        (store/commit-record! s {:effect :assignment-upsert
                                 :value {:id "a-100" :worker-id "w-200" :client-id "c-100"
                                         :jurisdiction :jpn :role "line-worker" :pay-rate 1200M
                                         :start-date "2023-08-01" :end-date "2026-08-01"
                                         :hazardous-duty? false :status :active}})
        (is (= "2026-08-01" (:end-date (store/assignment s "a-100")))))
      (testing "timesheet upsert commits approval"
        (store/commit-record! s {:effect :timesheet-upsert
                                 :value {:id "t-100" :assignment-id "a-100" :hours 160M
                                         :overtime-hours 0M :approved-amount 192000M :status :approved}})
        (is (= 192000M (:approved-amount (store/timesheet s "t-100")))))
      (testing "dispute-apply patches the target assignment"
        (store/commit-record! s {:effect :dispute-apply
                                 :value {:patch {:role "senior-line-worker"}}
                                 :path ["a-100"]})
        (is (= "senior-line-worker" (:role (store/assignment s "a-100")))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (take-last 2 (store/ledger s)))))))))

(deftest contract-lookup
  (doseq [[label s] (backends)]
    (testing label
      (is (= :tier/basic (:tier (store/contract s "tenant-c100"))))
      (is (true? (:active? (store/contract s "tenant-c100"))))
      (is (nil? (store/contract s "tenant-ghost"))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/worker s "nope")))
    (is (= [] (store/assignments-of-worker s "nope")))
    (is (= [] (store/ledger s)))
    (store/with-workers s {"x" {:id "x" :name "X" :eligibility nil}})
    (is (= "X" (:name (store/worker s "x"))))))
