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
  wage figure as if it were verified law.

  Citation verified 2026-07-22: FRA's tenure-cap required investigating,
  rather than assuming, which article actually sets the numeric cap today.
  Code du travail Art. L1251-12 (post-2018 'Ordonnances Macron' reform) no
  longer states a fixed duration itself -- it now only lets an extended
  branch-level collective agreement set the mission's total duration.
  The fixed 18-month default is instead in Art. L1251-12-1, which applies
  'à défaut de stipulation dans la convention ou l'accord de branche' (i.e.
  only as a FALLBACK when no branch agreement overrides it) -- confirmed by
  directly reading both articles on legifrance.gouv.fr. Citing L1251-12-1
  (the actual operative default), not L1251-12 (which a naive search would
  surface first but which no longer carries the number), avoids citing a
  superseded reading of the law. HIGH confidence.

  Citation verified 2026-07-22: KOR's tenure-cap was fetched directly from
  the government source rather than taken on faith from a secondary
  summary. law.go.kr's own English-language search UI (elaw.klri.re.kr)
  repeatedly 500'd on every query tried (POST to lawTotalSearch.do with the
  documented form fields), so the primary law.go.kr Open API
  (`https://www.law.go.kr/DRF/lawService.do?OC=test&target=law&MST=286257&type=XML`,
  `OC=test` is law.go.kr's own published anonymous-access sample id, no
  registration key was fabricated or guessed) was used instead -- it
  returned the full current consolidated text of 파견근로자 보호 등에 관한
  법률 (Act on the Protection of Dispatched Workers), 공포번호 21701,
  공포일자/시행일자 2026-05-26 (i.e. the version in force today), MST
  286257. Article 6 (파견기간, 'dispatch period') was read in full: ①
  caps ordinary dispatch (into the Art.5① permitted-occupation list) at 1
  year; ② allows a single extension by agreement of the dispatching
  business owner / using business owner / worker, capped so the *total*
  dispatch period including the extension does not exceed 2 years (24
  months); ③ carves out an exception letting the 2-year cap be exceeded
  for elderly workers; ④ sets a separate, shorter regime (period
  necessary, or ≤3 months extendable once) for the different
  temporary-vacancy/intermittent-need dispatch permitted under Art.5②
  -- confirmed by reading Art.5 alongside Art.6 so the cap is not
  overclaimed as universal. HIGH confidence.")

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
   {:id :fra-code-travail-tenure-cap
    :jurisdiction :fra :class :fra-mission-tenure-cap :covers #{:tenure-cap}
    :basis "Code du travail Art. L1251-12-1 — https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000035638866 — 18-month default maximum duration of a contrat de mission (including renewals), applying only where no extended branch-level collective agreement under Art. L1251-12 sets a different duration"
    :access :statute}
   {:id :kor-pagyeon-tenure-cap
    :jurisdiction :kor :class :kor-dispatch-tenure-cap :covers #{:tenure-cap}
    :basis "파견근로자 보호 등에 관한 법률(Act on the Protection of Dispatched Workers) 제6조(Art. 6) — https://www.law.go.kr/DRF/lawService.do?OC=test&target=law&MST=286257&type=HTML — 1-year default dispatch period (①), extendable once by agreement of the dispatching business owner/using business owner/worker so the total dispatch period including the extension does not exceed 2 years / 24 months (②); applies to dispatch into the Art. 5① permitted-occupation list, not to the separate shorter temporary-vacancy/intermittent-need dispatch permitted under Art. 5②/Art. 6④"
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
  proposal MarketData-LLM-style advisors must not be trusted to self-police.
  `:kor`'s 24 months is the *total* cap including the one allowed
  extension (Art. 6①②) for dispatch into the permitted-occupation list —
  it does NOT apply to the separate, shorter temporary-vacancy dispatch
  permitted under Art. 5②/Art. 6④, which this catalog does not encode as
  a single number since its duration is need-dependent, not fixed."
  {:jpn 36 :deu 18 :gbr 3 :fra 18 :kor 24})

(defn class-allowed? [source-class]
  (contains? allowed-eligibility-classes source-class))

(defn operator-verified-class? [source-class]
  (= :operator-verified-eligibility source-class))

(defn coverage
  "Honest, machine-checkable report of what R0 actually covers — never
  overstate ('全法域の派遣期間規制' in prose, 5 real tenure-cap statutes + 2
  real wage-compliance statutes + 1 real eligibility form + 1 structural
  operator-attested class, in fact)."
  []
  {:source-count (count catalog)
   :tenure-cap-jurisdictions (into (sorted-set) (keys tenure-cap-months))
   :wage-basis-jurisdictions (into (sorted-set) (map :jurisdiction (filter #(contains? (:covers %) :wage-compliance) catalog)))
   :note (str "R0 scope: 5 real tenure-cap statutes (JPN 労働者派遣法 3yr, "
              "DEU AÜG 18mo, GBR AWR 2010 12wk qualifying period, FRA Code "
              "du travail Art. L1251-12-1 18mo default, KOR 파견법 Art.6 "
              "1yr default/2yr total cap), 2 real "
              "wage-compliance statutory bases (USA FLSA, JPN 最低賃金法) -- "
              "actual numeric wage floors are operator-maintained data, "
              "never hardcoded here -- 1 real eligibility form (USA I-9), "
              "1 structural operator-attested eligibility class for other "
              "jurisdictions. USA deliberately has no tenure-cap entry -- "
              "no general federal duration cap exists to cite.")})
