# Operator Guide

This guide is for people who want to start an open business from
`cloud-itonami-isic-7820`.

## 1. Fork and Run

```bash
git clone https://github.com/cloud-itonami/cloud-itonami-isic-7820
cd cloud-itonami-isic-7820
clojure -M:dev:test
clojure -M:dev:run
```

The default demo uses entirely fictitious workers and clients. Production
worker/client records must stay outside the repository and be injected
through a store adapter, and every worker's eligibility must carry a real,
verifiable source citation.

## 2. Choose an Operating Mode

| Mode | Use when |
|---|---|
| Demo | validating the actor and governor contract |
| Self-host | one organization owns infrastructure and data |
| Managed tenant | an operator hosts for a client |
| Certified operator | itonami.cloud has reviewed security and process controls |

## 3. Production Checklist

- replace demo workers/clients with real, source-cited eligibility records
  (extend `staffing.facts/catalog` honestly for real statutory bases —
  never fabricate one — and maintain real, current numeric wage-floor
  rates in `staffing.store/wage-floors`, never hardcoded in this repo)
- configure Datomic Local, kotoba-server or an equivalent durable SSoT
- configure the LLM adapter through environment variables or secret manager
- define client billing tenants/tiers and RBAC rules
- run `clojure -M:dev:test`
- run `clojure -M:lint`
- verify audit-ledger export
- document backup and restore
- document incident response
- document the worker/client dispute-handling SLA
- get written legal review for every jurisdiction you place workers in
  (temp-agency licensing, tenure-cap/equal-treatment rules, wage and hour
  law, and worker-classification rules vary by jurisdiction)

## 4. Sales Motion

Start with a narrow offer:

1. onboard one real jurisdiction's eligibility-verification and
   wage-compliance basis (e.g. USA I-9 + FLSA)
2. prove governed placement → timesheet approval → billing report end to
   end
3. run one extension workflow in assisted mode (human-approved) to
   exercise the tenure-limit-gate honestly
4. export the audit ledger for review
5. convert to a metered or per-assignment margin contract

Avoid selling into a jurisdiction whose tenure-cap or wage-compliance basis
isn't yet in the statutory catalog — report coverage honestly
(`staffing.facts/coverage`), never oversell.

## 5. Certification Requirements

itonami.cloud certification should require:

- passing tests and lint on the published version
- written data-flow diagram (eligibility → governor → placement/billing)
- backup/restore evidence
- incident contact and response window
- proof that production placements/extensions/approvals/disclosures go
  through StaffingGovernor
- proof that real worker/client data is not stored in Git
- proof that a worker/client dispute channel exists and is human-reviewed
- customer-facing support and licensing terms

## 6. Operator Responsibilities

Operators are responsible for:

- lawful basis and licensing for temp-staffing operation in each
  jurisdiction served
- local labor/employment-law review (tenure caps, equal-treatment rules,
  wage and hour law, worker classification)
- secure infrastructure and tenant isolation
- honest statutory-catalog and wage-floor-table maintenance
- human review workflow for hazardous-duty and dispute-request operations
- data-retention policy
- security updates

The OSS project provides software and an operating blueprint. It does not
make an operator compliant by itself, and it does not license or endorse
staffing operations in any jurisdiction beyond what the operator has
independently verified.
