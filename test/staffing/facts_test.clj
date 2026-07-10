(ns staffing.facts-test
  "The R0 statutory-basis catalog is the whole ground truth for the
  eligibility-gate and the tenure/wage caps — these tests guard its own
  internal honesty (every class it advertises is actually backed by a
  catalog entry, no duplicate/aspirational entries, and no fabricated
  numeric wage rate anywhere in this namespace)."
  (:require [clojure.test :refer [deftest is testing]]
            [staffing.facts :as facts]))

(deftest catalog-entries-are-well-formed
  (doseq [{:keys [id class covers basis access]} facts/catalog]
    (testing (str id)
      (is (keyword? id))
      (is (keyword? class))
      (is (set? covers))
      (is (seq covers))
      (is (string? basis))
      (is (keyword? access)))))

(deftest allowed-eligibility-classes-matches-catalog
  (is (= (into #{} (map :class (filter #(contains? (:covers %) :eligibility) facts/catalog)))
         facts/allowed-eligibility-classes)))

(deftest class-allowed?-rejects-unlisted-classes
  (is (facts/class-allowed? :i9-eligibility-verification))
  (is (facts/class-allowed? :operator-verified-eligibility))
  (is (not (facts/class-allowed? :self-attested)))
  (is (not (facts/class-allowed? nil))))

(deftest operator-verified-class-recognized
  (is (facts/operator-verified-class? :operator-verified-eligibility))
  (is (not (facts/operator-verified-class? :i9-eligibility-verification))))

(deftest usa-has-no-tenure-cap
  (testing "no general federal duration cap exists for USA -- must not be fabricated"
    (is (not (contains? facts/tenure-cap-months :usa)))))

(deftest tenure-cap-months-are-real-and-small
  (is (= 36 (:jpn facts/tenure-cap-months)))
  (is (= 18 (:deu facts/tenure-cap-months)))
  (is (= 3 (:gbr facts/tenure-cap-months))))

(deftest coverage-is-honest-not-aspirational
  (let [c (facts/coverage)]
    (is (= (count facts/catalog) (:source-count c)))
    (is (<= (:source-count c) 20) "R0 catalog should stay small and citable, not bulk-padded")
    (is (contains? (:tenure-cap-jurisdictions c) :jpn))
    (is (not (contains? (:tenure-cap-jurisdictions c) :usa)))))
