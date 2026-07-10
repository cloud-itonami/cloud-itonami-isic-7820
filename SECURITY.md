# Security Policy

This project handles worker eligibility, assignment, timesheet and payroll-
approval data. Treat vulnerabilities as potentially high impact even when
the demo data is synthetic — an eligibility or wage-compliance bypass
reaching payroll has direct legal and financial consequences.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential or eligibility-document exposure
- StaffingGovernor bypass (eligibility-gate, tenure-limit-gate,
  wage-compliance-gate, licensed-disclosure)
- audit-ledger tampering
- over-disclosure beyond a client contract's tier
- tenant isolation failures
- placement/extension of a worker without a valid eligibility citation
- approval of a timesheet below the jurisdiction's wage floor

## Reporting

Use GitHub private vulnerability reporting when available for the
repository. If that is unavailable, contact the repository maintainers
through the cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on worker/client data, governor enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets and eligibility-verification records outside Git.
- Run governor tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for staffing coordinators, payroll officers and
  service accounts.
- Alert on any tenure-limit-gate or wage-compliance-gate HOLD spike — it
  may indicate a data-quality or rate-table problem upstream.
