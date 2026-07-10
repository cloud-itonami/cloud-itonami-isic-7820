(ns staffing.facts
  "R0 statutory-basis catalog — the ONLY provenance classes the
  StaffingGovernor will accept for a worker's eligibility verification, and
  the ONLY jurisdictions the tenure-limit-gate and wage-compliance-gate
  enforce a real statutory cap for (mirrors `cloud-itonami-isic-8291`'s
  `dossier.facts` / `cloud-itonami-isic-6311`'s `marketdata.facts`
  discipline: honesty over coverage).

  Two kinds of entry:
    1. Real, citable statutory bases (eligibility-verification regime,
       tenure-cap statute, or wage-compliance statute) for a specific
       jurisdiction — genuinely real law, cited as a basis, NOT asserted as
       a currently-accurate numeric rate (minimum-wage rates and similar
       numbers move too often and too locally to hardcode honestly; the
       actual current threshold is operator-maintained data in the store,
       see `staffing.store/wage-floors`).
    2. `:operator-verified-eligibility` — the structural class for a
       jurisdiction whose eligibility-verification regime this catalog does
       not itself encode. A worker's eligibility citing this class is only
       accepted when it carries a `:verification-ref` the operator attests
       to under their own jurisdiction's law (same operator-supplies-the-
       actual-compliance-artifact boundary as `marketdata.facts`'s
       `:licensed-operator-feed`).

  Adding coverage means adding a real, citable statutory entry — never
  fabricating one, and never asserting a specific current dollar/yen/euro
  wage figure as if it were verified law.")

(def catalog
  "Each entry: {:id :jurisdiction :class :covers :basis :access}. `:class`
  is the value that must appear in a worker's `:eligibility :class` (for
  `:covers :eligibility`) to satisfy the eligibility-gate, OR the value a
  jurisdiction's `:tenure-cap-months`/`:wage-basis` table entries are
  grounded by (for `:covers :tenure-cap`/`:wage-compliance`)."
  [{:id :usa-i9-eligibility-verification
    :jurisdiction :usa :class :i9-eligibility-verification :covers #{:eligibility}
    :basis "Form I-9, Immigration and Nationality Act (INA) §274A, 8 U.S.C. §1324a"
    :access :federal-form}
   {:id :usa-flsa-wage-basis
    :jurisdiction :usa :class :flsa-wage-basis :covers #{:wage-compliance}
    :basis "Fair Labor Standards Act, 29 U.S.C. §206 (minimum wage) / §207 (overtime, 1.5x over 40hrs/week)"
    :access :federal-statute}
   {:id :jpn-haken-tenure-cap
    :jurisdiction :jpn :class :haken-tenure-cap :covers #{:tenure-cap}
    :basis "労働者派遣法(Worker Dispatching Act)第40条の2 — individual-basis 3-year (36-month) limit on continuous dispatch to the same organizational unit"
    :access :statute}
   {:id :jpn-minimum-wage-basis
    :jurisdiction :jpn :class :jpn-minimum-wage-basis :covers #{:wage-compliance}
    :basis "最低賃金法(Minimum Wage Act)"
    :access :statute}
   {:id :deu-aug-tenure-cap
    :jurisdiction :deu :class :aug-tenure-cap :covers #{:tenure-cap}
    :basis "Arbeitnehmerüberlassungsgesetz (AÜG) §1 Abs.1b — 18-month Überlassungshöchstdauer (max hiring-out duration to the same Entleiher)"
    :access :statute}
   {:id :gbr-awr-qualifying-period
    :jurisdiction :gbr :class :awr-qualifying-period :covers #{:tenure-cap}
    :basis "Agency Workers Regulations 2010, reg. 5 — 12-week qualifying period before equal-treatment entitlement attaches"
    :access :statute}
   {:id :operator-verified-eligibility
    :jurisdiction nil :class :operator-verified-eligibility :covers #{:eligibility}
    :basis "operator-attested verification performed under the operator's own jurisdiction law (structural class, not a specific statute)"
    :access :operator-attested}])

(def allowed-eligibility-classes
  (into #{} (map :class (filter #(contains? (:covers %) :eligibility) catalog))))

(def tenure-cap-months
  "jurisdiction → statutory cap on continuous assignment duration, in
  months, grounded by a `catalog` entry with `:covers :tenure-cap`.
  Deliberately has NO entry for `:usa` — there is no general federal cap on
  temp-agency assignment duration (co-employment/ACA hours-threshold rules
  exist but are not a duration cap in the same sense); asserting one would
  be fabricating a source. `:gbr`'s 12 weeks ≈ 3 months is a qualifying-
  period trigger (equal treatment attaches), not an absolute ban past that
  point, but this actor still treats crossing it as HARD — extending past
  it without adjusting terms to equal treatment is exactly the kind of
  proposal MarketData-LLM-style advisors must not be trusted to self-police."
  {:jpn 36 :deu 18 :gbr 3})

(defn class-allowed? [source-class]
  (contains? allowed-eligibility-classes source-class))

(defn operator-verified-class? [source-class]
  (= :operator-verified-eligibility source-class))

(defn coverage
  "Honest, machine-checkable report of what R0 actually covers — never
  overstate ('全法域の派遣期間規制' in prose, 3 real tenure-cap statutes + 2
  real wage-compliance statutes + 1 real eligibility form + 1 structural
  operator-attested class, in fact)."
  []
  {:source-count (count catalog)
   :tenure-cap-jurisdictions (into (sorted-set) (keys tenure-cap-months))
   :wage-basis-jurisdictions (into (sorted-set) (map :jurisdiction (filter #(contains? (:covers %) :wage-compliance) catalog)))
   :note (str "R0 scope: 3 real tenure-cap statutes (JPN 労働者派遣法 3yr, "
              "DEU AÜG 18mo, GBR AWR 2010 12wk qualifying period), 2 real "
              "wage-compliance statutory bases (USA FLSA, JPN 最低賃金法) -- "
              "actual numeric wage floors are operator-maintained data, "
              "never hardcoded here -- 1 real eligibility form (USA I-9), "
              "1 structural operator-attested eligibility class for other "
              "jurisdictions. USA deliberately has no tenure-cap entry -- "
              "no general federal duration cap exists to cite.")})
