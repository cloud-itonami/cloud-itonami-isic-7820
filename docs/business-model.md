# Open Business Blueprint: cloud-itonami-isic-7820

This repository publishes an OSS business model for operating a temporary
employment agency (Randstad / Adecco / ManpowerGroup class) on
itonami.cloud: the agency remains the employer of record and dispatches
workers to client companies for time-bounded assignments, billing the
client and paying the worker.

## Classification

- Repository name: `cloud-itonami-isic-7820`
- Primary classification: ISIC Rev.4 7820
- Activity: temporary employment agency activities — the agency is the
  employer of record; workers are dispatched to client companies for
  assignments of bounded duration
- Served domain: worker eligibility verification, assignment placement and
  extension, timesheet-based payroll approval, client billing reports,
  worker/client dispute resolution

ISIC 7820 is distinct from
[`cloud-itonami-isic-7810`](https://github.com/cloud-itonami/cloud-itonami-isic-7810)
(activities of employment placement agencies — a one-time match-and-fee
service where the agency is never the employer of record) and from
`cloud-itonami-6310` (internal HR/talent SaaS, not a staffing agency). This
is the 6th `:spec → real repo` promotion in `kotoba-lang/industry`'s
registry (after `cloud-itonami-M6910`'s 6910, `cloud-itonami-isic-8291`'s
8291, `cloud-itonami-isic-4690`'s 4690, `cloud-itonami-isic-4610`'s 4610,
and `cloud-itonami-isic-6311`'s 6311).

## Customer

Primary customers:

- host companies needing flexible, compliant temporary staffing (warehouse,
  light-industrial, logistics, hospitality, seasonal roles)
- workers seeking assignment-based employment through a compliant employer
  of record
- other `cloud-itonami-{ISIC}` blueprint operators needing a compliant
  temp-workforce channel

## Problem

Traditional staffing agencies operate opaque back-office compliance (do
we know this worker's eligibility is current? are we about to breach the
jurisdiction's tenure cap? is this timesheet's effective rate actually
above the wage floor?) with no structural guarantee against silent
compliance drift — a placement that quietly exceeds a statutory duration
cap, or a payroll calculation bug that underpays overtime, can run for
months before anyone notices.

## Offer

Operators provide an OSS actor for temp-staffing operations:

- worker eligibility verification, source-cited (real form, e.g. USA I-9,
  or an operator-attested verification record for other jurisdictions)
- assignment placement and extension, gated by a real statutory
  tenure-cap table (JPN 労働者派遣法, DEU AÜG, GBR AWR 2010)
- timesheet-based payroll amount computation, gated by an
  operator-maintained wage-floor reference and a real statutory basis
  (USA FLSA, JPN 最低賃金法)
- governed, tier-scoped client billing reports (never a public/anonymous
  query surface)
- a worker/client dispute channel, always human-reviewed
- immutable audit ledger of every placement/extension/approval/disclosure
  event
- structural exclusion of payroll disbursement — this actor computes
  amounts, it never moves money

## Revenue

Operators can sell:

- markup on billed worker hours (the standard staffing-agency margin
  model: bill rate − pay rate)
- tiered client billing access: `:tier/basic` (hours/amount only) →
  `:tier/detailed` (+ worker identity/pay-rate) → `:tier/audit` (+
  eligibility/jurisdiction basis)
- managed hosting: monthly subscription per client tenant
- compliance package: audit export, dispute-handling SLA, security review

| Package | Customer | Price shape |
|---|---|---|
| Basic billing feed | small host company | per-assignment or low monthly tier |
| Detailed tier | mid-size client with own HR review | monthly platform fee |
| Audit tier | client with compliance/legal review needs | monthly fee + usage |
| Fleet wholesale | other cloud-itonami operators | API metering |
| Managed Starter | 稼働スタッフ100–300名・内勤5–15名の中小派遣会社1社 | ¥60,000/月 flat |

**Market-anchored (2026-08-10)**: benchmarked against the real 派遣管理
システム (temp-staffing management system) market — the adjacent commercial
category to this actor — not against generic HR SaaS. Of the products
surveyed, **only 3 vendors publish real numbers on their own sites**:

- **HRstation** (アルティウスリンク/KDDIグループ) — 「初期費用・導入費用は無料」
  「スタッフ一人につき月額最大800円（税抜）」, and 「1か月のご利用日数が15日以下の
  ユーザーについては、月額400円（税抜）」; the 派遣元 pays, the 派遣先 uses it free
  (<https://www.altius-link.com/hrstation/price/>). At 100–300 稼働スタッフ that
  is **¥80,000–240,000/月**.
- **MatchinGood** — 「初期費用 無料」「月額 ¥22,000（税込）〜」, plan-dependent
  (<https://www.matchingood.co.jp/price/>). **¥22,000〜/月**; the upper bound is
  not published.
- **PORTERS** — 「初期費用＋月額利用料の、シンプルな料金体系です。最低15,000円～
  ユーザー数に応じて費用が変動します」 on the vendor's own plan page
  (<https://hrbc.porters.jp/plan/>); the per-ID tier break (初期100,000円 /
  1ID月額15,000円 / 11ID以降7,500円) appears only in aggregator listings
  (<https://boxil.jp/mag/a8309/>). At 5–15 内勤ID that is **¥75,000–187,500/月**.

**e-staffing publishes nothing** — its own site has no pricing page at all
(<https://www.e-staffing.co.jp/>); the widely-quoted 「1,000円/スタッフ」 is
aggregator-reported, not vendor-published. So are **staff-one** (初期300,000円 +
月額20,000円+200円/人), **CROSS STAFF** (月額30,000円), **jobs** (月額33,000円) and
**ｅ心伝心** (月額30,000円). **スタッフエクスプレス, CastingONE, 派遣can,
The Staff-V, DigiSheet and most of the rest disclose nothing publicly** and route
to a quote form. The observed market therefore splits into a flat-fee entry band
(**¥22,000–33,000/月**) and a per-staff compliance band
(**¥80,000–240,000/月** at our assumed size).

**Why the ¥50,000–150,000/月 range used by the HR/recruiting siblings
(`cloud-itonami-isic-7810`, `6399`, `6310`, `5820`) is deliberately NOT carried
over here**: the billing driver in 派遣 is **稼働スタッフ数, not 内勤の席数** —
HRstation's ¥800/人 and e-staffing's reported ¥1,000/人 are what actually price
this market, and both scale with dispatched workers rather than recruiter seats.
`7810` (職業紹介) is a one-time match-and-fee business where the agency is never
the employer of record, and its band was anchored on per-seat HR/recruiting/CRM
SaaS. Those comparators have no evidenced relationship to 抵触日/3年ルール
compliance pricing, so importing that number would be an unfounded figure
dressed as a benchmark.

**¥60,000/月 flat** sits just below the geometric centre of the measured
¥22,000–240,000 range, biased low. It is above every flat-fee entry product
because none of those carries statutory judgment (they are 単機能の管理CRM/勤怠).
It is below the per-staff compliance band because this actor does **not** collect
勤怠打刻, does **not** generate or retain 法定帳票 (抵触日通知書 etc., which
e-staffing keeps for 12年4ヶ月), does **not** issue invoices, and by construction
**never disburses payroll or moves money** — it computes an approved amount and
gates a placement. Pricing is flat rather than per-staff because what is being
sold is not per-head paperwork throughput but a single property no comparator
has: an independent StaffingGovernor that can permanently refuse a placement or
extension that would breach a tenure cap or a wage floor, on a citable statutory
basis, with the refusal recorded in an immutable ledger.

**Subscribe (2026-08-10)**: a live Stripe Payment Link for the Managed Starter
tier (¥60,000/月 flat) is available now — [**subscribe to Managed Starter**](https://buy.stripe.com/5kQ00keuXeRO3va922eEo0n).
This is a no-code Stripe-hosted checkout; nothing in this repo's actor code
changed. After subscribing, contact gftdcojp to arrange managed-tenant setup
(manual fulfillment today, no automated onboarding yet). **No staffing agency
has claimed or subscribed to this tier yet — this is a live, working checkout
with zero paid tenants, not a claim of existing revenue.**

## Unit Economics

Track these numbers for every operator:

- eligibility-verification and onboarding hours per new worker
- monthly infrastructure cost
- LLM cost per operation (place / extend / approve / report)
- dispute-handling hours per client
- gross margin (bill rate − pay rate) after infrastructure and support
- churn and expansion revenue per client tier

The business should only scale after the statutory catalog is genuinely
citable (never fabricated) and governor tests catch tenure/wage/
eligibility misconfiguration before production use.

## Open Participation

Anyone may:

- fork the repository
- run the demo
- deploy a self-hosted instance
- submit issues and patches
- publish compatible statutory-catalog extensions (real, citable law only)
- create a local operator business

itonami.cloud should require certification before listing an operator as a
trusted provider, routing customer leads, or allowing managed disclosure
under the platform brand.

## Operator Trust Levels

| Level | Capability |
|---|---|
| Contributor | patches, docs, issues, examples |
| Self-host operator | runs their own instance with no platform endorsement |
| Certified operator | listed on itonami.cloud after review |
| Managed operator | may receive leads and operate customer tenants |
| Core maintainer | can approve changes to governor, security and governance |

## Marketplace Metadata

Suggested itonami.cloud metadata:

```edn
{:itonami.blueprint/id "cloud-itonami-isic-7820"
 :itonami.blueprint/name "Temporary Employment Agency Actor"
 :itonami.blueprint/isic-rev4 "7820"
 :itonami.blueprint/domain :labor/temp-staffing
 :itonami.blueprint/license "AGPL-3.0-or-later"
 :itonami.blueprint/operator-model :certified-open-business
 :itonami.blueprint/repo "https://github.com/cloud-itonami/cloud-itonami-isic-7820"
 :itonami.blueprint/status :public-oss
 :itonami.blueprint/required-technologies [:identity :forms :audit-ledger :labor]
 :itonami.blueprint/optional-technologies [:dmn :bpmn]}
```

## Non-Negotiables

- Do not commit real worker records, real client contract documents, or
  real eligibility-verification documents.
- Do not add a schema field for payroll disbursement, bank transfer or tax
  withholding execution.
- Do not bypass the StaffingGovernor for production placements, extensions,
  approvals or disclosures.
- Do not serve a client report to a tenant without an active, registered
  contract.
- Do not fabricate a statutory-basis catalog entry or a numeric wage-floor
  value.
- Do not market an uncertified deployment as an itonami.cloud certified
  operator.
