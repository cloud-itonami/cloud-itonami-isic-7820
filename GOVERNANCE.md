# Governance

`cloud-itonami-isic-7820` is an OSS open-business blueprint. Governance
covers both code and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- TempStaffing-LLM cannot directly place, extend, approve or resolve a
  dispute.
- StaffingGovernor remains independent of the advisor.
- hard governor violations (eligibility-gate, tenure-limit-gate,
  wage-compliance-gate, licensed-disclosure) cannot be overridden by human
  approval.
- a worker/client dispute never auto-resolves, at any rollout phase.
- every commit, hold and disclosure event is auditable.
- no schema field exists for payroll disbursement, bank transfer or tax
  withholding execution — scope is structural, not a runtime filter
  someone could forget to call.
- real worker/client data and real eligibility-verification documents stay
  outside Git.
- no fabricated numeric wage-floor value ever appears in the statutory
  catalog — only real statutory citations; the current rate is always
  operator-maintained.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, disclosure scope, public business model, operator
certification or license should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit, support and
data-flow review.

Certified operators can lose certification for:

- bypassing governor checks
- placing or extending a worker without valid eligibility
- disclosing data to an uncontracted client
- approving a timesheet below the jurisdiction's wage floor
- misrepresenting certification status
- failing to respond to security incidents or worker/client disputes
- hiding material changes to customer-facing operation
