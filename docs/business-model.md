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
