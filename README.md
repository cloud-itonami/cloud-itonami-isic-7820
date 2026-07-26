# cloud-itonami-isic-7820

Open Business Blueprint for **ISIC Rev.4 7820**: temporary employment
agency activities — the Randstad / Adecco / ManpowerGroup class of
business, where the agency remains the employer of record and dispatches
workers to client companies for time-bounded assignments. This repository
publishes that as an OSS business that any qualified operator can fork,
deploy, run, improve and sell.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph) StateGraph runtime
(portable `.cljc`, supervised superstep loop, interrupts, Datomic/in-mem
checkpoints) — the same actor pattern as
[`cloud-itonami-isic-8291`](https://github.com/cloud-itonami/cloud-itonami-isic-8291)
and
[`cloud-itonami-isic-6311`](https://github.com/cloud-itonami/cloud-itonami-isic-6311).

> **Why an actor layer at all?** A TempStaffing-LLM is great at drafting a
> placement, computing a proposed timesheet payroll amount, and proposing
> client-report column sets — but it has **no notion of statutory tenure
> caps, wage-floor compliance, or a client's disclosure entitlement**.
> Letting it place or extend an assignment directly invites a worker being
> kept at the same client past the jurisdiction's statutory conversion-
> rights trigger, a payroll amount that silently underpays overtime, or a
> report leaking a worker's identity beyond a contract's tier. This
> project seals the TempStaffing-LLM into a single node and wraps it with
> an independent **StaffingGovernor**, a human **review workflow**, and an
> immutable **audit ledger**.

## Scope (deliberately narrow — read this before anything else)

This actor **places, extends, times, reports on and resolves disputes for**
temp-staffing assignments. It never disburses payroll, moves money, or
executes a bank transfer — there is no field anywhere in this schema for
payment execution (see `docs/adr/0001-architecture.md`). It computes and
records an *approved amount*; a downstream payroll system executes the
actual payment. Statutory tenure-cap and wage-compliance bases are limited
to real, citable law (`src/staffing/facts.cljc`) — the actual current
numeric wage-floor rate is always operator-maintained data, never
hardcoded.

## Consuming this actor from another blueprint

`:report/query` is the governed read surface — a client company's billing
report, columns limited to its contract tier. It always runs through the
StaffingGovernor's licensed-disclosure check — there is no bypass.

See [`docs/DESIGN.md`](docs/DESIGN.md) for the full architecture and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
decision record. See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an open
business on itonami.cloud.

## Open business

This repository is not only source code. It is a public, forkable business
model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, StaffingGovernor, governed disclosure, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, deploy, support and sell the service |
| Trust controls | Governance, security reporting, policy tests, audit requirements |

The primary industry classification is **ISIC Rev.4 7820** because the
commercial activity is dispatching agency-employed workers to client
companies for temporary assignments — distinct from
[`cloud-itonami-isic-7810`](https://github.com/cloud-itonami/cloud-itonami-isic-7810)'s
one-time placement-fee model (the agency is never the employer of record
there) and from `cloud-itonami-6310`'s internal HR/talent SaaS.

## The core contract

```
request + injected role/tenant/phase context
        │
        ▼
   ┌───────────────┐    proposal      ┌─────────────────────┐
   │ TempStaffing-  │ ───────────────▶│ StaffingGovernor     │  (independent system)
   │ LLM (sealed)   │  draft + source │  eligibility · tenure│
   └───────────────┘   citation       │  · wage · human       │
                                       └─────────────────────┘
                                              │
                                   commit / serve only if allowed
                                              ▼
                                    append-only audit ledger
```

**Single invariant**: TempStaffing-LLM never places, extends, approves, or
resolves a dispute the StaffingGovernor would reject.

## Where the workers come from (hiring intake)

A dispatch agency needs people before it can dispatch anyone. Until now
workers could only be seeded, so "the workforce exists" was an assumption.
Three governed ops close that, and they are also the **receiving end of the
referral seam** ADR-2607131000 / ADR-2607202600 define — an occupation
actor (`cloud-itonami-isco-*`) whose robot structurally cannot do a piece
of on-site work emits a referral draft, a human carries it here, and this
op records it:

| op | who may run it | what it does |
|---|---|---|
| `:candidate/intake` | staffing-coordinator, hiring-manager | records a candidate + **how they arrived** (direct application, or a referral draft naming the origin actor and its draft id). Employs nobody. |
| `:worker/hire` | hiring-manager only | this agency becomes the employer of record for that person. |
| `:worker/decline` | hiring-manager only | turns the candidate down, keeping the record. |

Structural, not procedural:

- **A candidate is not dispatchable.** Candidates live in their own
  container, so `worker`/`assignments-of-worker` cannot return someone who
  was never hired — a placement aimed at a candidate is a HARD
  `:unknown-worker` hold.
- **Employment is always a human's decision.** `:worker/hire` and
  `:worker/decline` are absent from every phase's `:auto` set and always
  escalate in the governor, at any confidence — the same treatment
  `:dispute/request` gets. Becoming someone's employer of record is not a
  routing optimization.
- **The eligibility gate runs at hire, not only at dispatch.** The same
  closed catalog (`staffing.facts`) and the same `:verification-ref`
  requirement for operator-verified classes apply to the hire itself, and
  they hold BEFORE a human is asked — an approver cannot wave through a
  hire with no work-authorization citation.
- **No applicant personal data.** A candidate carries a self-chosen
  `:handle` and an opaque `:contact-ref` (a pointer to the public thread).
  The legal name and eligibility citation enter only at hire, which a human
  performs. There is still no field anywhere for a bank account or tax id —
  this actor never moves money (ADR-2607111600 §1).
- **Provenance is a claim recorded on this side only.** The governor checks
  that a referral names a fleet actor (`cloud-itonami-isco-NNNN` or one of
  the named staffing/matching actors) and carries a draft id. It does *not*
  call that actor to confirm the draft exists — that cross-actor
  invocation is exactly what ADR-2607131000 forbids. Reconciling both
  ledgers is an operator's job, by hand, on purpose.

What this does **not** do: execute an employment contract, or pay anyone.
Hiring here means "this person is now on our books as dispatchable".

## Run

```bash
clojure -M:dev:test   # governor contract · store parity · phases · facts
clojure -M:dev:run    # 11-operation demo through one OperationActor
clojure -M:lint
```

## Non-Negotiables

- Do not make `:worker/hire` or `:worker/decline` auto-committable at any
  phase, and do not add a confidence threshold that skips the human.
- Do not add a schema field for applicant personal data (address, phone,
  email, date of birth, national id) or for a bank account / tax id.
- Do not commit real worker records, real client contract documents, or
  real eligibility-verification documents.
- Do not add a schema field for payroll disbursement, bank transfer or tax
  withholding execution.
- Do not bypass the StaffingGovernor for production placements, extensions,
  approvals or disclosures.
- Do not serve a client report without an active, registered contract.
- Do not fabricate a statutory-basis catalog entry or a numeric wage-floor
  value.

License: AGPL-3.0-or-later.
