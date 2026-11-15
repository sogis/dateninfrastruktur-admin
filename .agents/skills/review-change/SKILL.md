---
name: review-change
description: Independently review a proposed code change for correctness, regressions, security, and test coverage.
---

Read the request and applicable repository instructions. Inspect the actual diff
and its callers against the stated base. Trace changed behavior through its usual
entry points and check the contracts of the libraries it uses.

Leave product files untouched. Run targeted existing checks for concrete risks.
Use an independent countercheck when a specific hypothesis warrants it. Use
simplify-change in the same review round for a larger change.

Report the reviewed revision and scope. Order actionable findings by impact and
give the file, method, triggering condition, consequence, and necessary
correction. List checks actually run, material checks omitted, remaining risks,
and an overall assessment. Use one Markdown report.
