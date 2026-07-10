# Contributing

`cloud-itonami-isic-7820` accepts contributions to the OSS actor, governor
tests, documentation, examples and open business blueprint.

## Development

```bash
clojure -M:dev:test
clojure -M:lint
```

Keep changes small and include tests for governor, audit, store or
disclosure behavior.

## Rules

- Do not commit real worker records, real client contract documents, or
  real eligibility-verification documents.
- Keep production placements, extensions, timesheet approvals and
  disclosures behind StaffingGovernor.
- Treat every new jurisdiction as high-risk: add tests for eligibility-gate,
  tenure-limit-gate, wage-compliance-gate, licensed-disclosure and audit
  logging.
- Never fabricate a statutory-basis catalog entry or a numeric wage-floor
  value — the legal basis citation must be real; the actual current rate
  is always operator-maintained data (`staffing.store/wage-floors`).
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which governor invariant is affected
- how it was tested
- whether operator or certification docs need updates
