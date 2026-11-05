# Feature test matrix

Every user-facing feature found in the UI routes, the Core and DPS controllers and the PRD, with how it was tested. "E2E n" is the numbered test in `ui/e2e/app.spec.ts`. Other IDs are in `test-results.md`. Status values: Pass, Fail, Partial, Not verified.

| Test ID | Feature | Steps | Expected | Actual | Status | Evidence |
|---|---|---|---|---|---|---|
| F-01 | Sign-in and role menus | E2E 1, 13; roles.spec for 6 roles | Each role sees its own menus; no admin menus for RM | As expected | Pass | E2E 1, 13; roles.spec 11/11 |
| F-02 | Sign-out and stale sign-in | E2E 14, 33 | Back to sign-in; stale reply restarts sign-in | As expected | Pass | E2E 14, 33 |
| F-03 | Portfolio (RM) | Open /portfolio as rm-anil | Own CPs, statuses, charts | As expected, 12 CPs | Pass | E2E 1, 22 |
| F-04 | Portfolio (bank-wide roles) | admin and auditor open portfolio with 25 and with 2,225 CPs | Whole bank | 25 CPs: Pass. 2,225 CPs: HTTP 500 | **Fail** | LOAD-08, BUG-02 |
| F-05 | Portfolio speed for large portfolios | One RM with 60 CPs, then 25 concurrent | Pages within a few seconds | 4–15 s alone; 504 and 30 s pool time-outs at 25 users | **Fail** | LOAD-09, LOAD-10, BUG-03 |
| F-06 | CP workspace: covenants, trend, schedule | E2E 15 | Grid, trend arrow, schedule | As expected | Pass | E2E 15 |
| F-07 | CP picker and lists without a CP | roles.spec:154 | User's CPs to choose from, search works | As expected | Pass | roles.spec |
| F-08 | Agreement upload (Flow A) | E2E 3; PERF-01 | Job runs, terms extracted with page and quote | PUBLISHED in 4–9 s for 50 pages | Pass | E2E 3, PERF-01 |
| F-09 | Extraction correctness: operators | AI-07 with five common phrasings | Floors give `>=`, ceilings `<=` | "shall not be less than" gives `<` | **Fail** | BUG-01 |
| F-10 | Citation check (FR-03) | AI-01 on 10 golden agreements | Quote on page, threshold in quote | 0 failures | Pass | AI-01 |
| F-11 | Extraction coverage | AI-02 | All covenants found | Non-ratio numeric clauses missed | Partial | RISK-01 |
| F-12 | Prompt injection in an agreement | AI-03 | Extraction and answers ignore it | Extraction ignores it; Q&A returns it | Partial | AI-03, BUG-12 |
| F-13 | Extraction review: edit, approve, reject | E2E 4; UI unit tests | Actions saved and audited | As expected | Pass | E2E 4 |
| F-14 | Onboarding with EXTRACTED rows (FR-06) | E2E 4 | Rows labelled EXTRACTED with source link | As expected | Pass | E2E 4 |
| F-15 | Onboarding visibility in portfolio | Apply covenants by API without the recommendation step | CP appears in RM portfolio | CP missing from portfolio and tasks | **Fail** | BUG-06 |
| F-16 | Statement upload (Flow B) and spread | E2E 5, 29; PERF-02 | Values with cell references, full spread | As expected, 2–3 s | Pass | E2E 5, 29 |
| F-17 | Units and currency | PERF-06 | Lakhs converted to crore | As expected | Pass | PERF-06 |
| F-18 | Values API (C2) | PERF-04 | p95 ≤ 500 ms | p95 83 ms | Pass | PERF-04 |
| F-19 | Re-assessment on new data (FR-09) | PERF-03 | Within 5 minutes | 0.2–1.9 s | Pass | PERF-03 |
| F-20 | Missing data never breaches (FR-10) | Core gate tests | ERROR with diagnostic | As expected | Pass | Core test suite |
| F-21 | Assessment calculations | AI-08 spot checks against source tables | Values and results match source | Match | Pass | AI-08 |
| F-22 | Manual covenant result with evidence (FR-11) | E2E 16 | Result and note saved | As expected | Pass | E2E 16 |
| F-23 | Covenant change | E2E 20 | Change saved and audited | As expected | Pass | E2E 20 |
| F-24 | Waiver / extension maker-checker (FR-13, FR-19) | E2E 6-8, 21 | RM requests, cannot decide; approver decides; RM can withdraw | As expected | Pass | E2E 6-8, 21 |
| F-25 | Manual entry of a missing value (FR-20) | DPS and Core tests | USER-sourced value, re-assessment, badge | As expected | Pass | Test suites |
| F-26 | One agreement linked to several CPs (FR-21) | DPS tests | Proposals offered on each CP | As expected | Pass | Test suites |
| F-27 | Certificates and borrower portal (FR-22) | E2E 25, 30, 31; roles.spec:110 | Request, submit signed certificate, portal-only view | As expected | Pass | E2E 25, 30, 31 |
| F-28 | Reports: Excel and PDF (FR-24, FR-32) | E2E 9 | Both download | As expected | Pass | E2E 9 |
| F-29 | Custom saved report | E2E 28 | Runs, saves, downloads, deletes | As expected; no schedule | Partial | E2E 28, GAP-10 |
| F-30 | Breach notice as Word | E2E 27 | .docx downloads | As expected | Pass | E2E 27 |
| F-31 | Open-breaches report with escalation | LOAD-10 | Report lists escalations | Correct but 4–6 s for 60 CPs; 500 above 2,100 CPs bank-wide | Partial | BUG-02, BUG-03 |
| F-32 | Escalation notification (FR-31) | Code review | Notification to credit head | Not built | **Fail** | GAP-02 |
| F-33 | Analytics and alerts (FR-25) | E2E 22 | Treemap, exposure by sector, entity mix | Breach-rate charts only | Partial | GAP-08 |
| F-34 | Trend indicator (FR-26) | E2E 15 | Improving / stable / worsening | As expected | Pass | E2E 15 |
| F-35 | Ask AI (FR-27) | E2E 10; AI-06 | Portfolio-scoped, cited, refuses outside scope | As expected | Pass | AI-06 |
| F-36 | Ask a question about an agreement | E2E 26; AI-03, AI-04 | Answer from the right clause or "not stated" | Returns planted or irrelevant text | **Fail** | BUG-12 |
| F-37 | Advisory narrative label (FR-15) | UI check | "AI-generated, not a decision" | As expected | Pass | AI-09 |
| F-38 | Financial news (FR-28) | n/a | Optional | Not built | Not verified | GAP-09 |
| F-39 | Covenant library, usage, measures, data sources | E2E 11, 17, 18 | Lists, downloads, where-used | As expected | Pass | E2E 11, 17, 18 |
| F-40 | My tasks | E2E 24; LOAD runs | Task list and notes | Works; 500 after large daily runs; slow for large portfolios | Partial | BUG-05, BUG-03 |
| F-41 | Document removal | E2E 23 | Removed with reason, history kept | As expected | Pass | E2E 23 |
| F-42 | File validation | DOC-01..DOC-10 | Bad files refused or fail cleanly; ≤ 500 pages | All clean except 600 pages accepted | Partial | BUG-11 |
| F-43 | Failure reason for the RM | DOC-01 | RM told why a file failed | Only "Failed" | **Fail** | BUG-13 |
| F-44 | Concurrent uploads on SQL Server | LOAD-06, LOAD-06b | Uploads succeed under load | 22–70% fail with deadlock 1205 | **Fail** | BUG-04 |
| F-45 | Support: document jobs, diagnose a CP, sent e-mails (FR-17) | E2E 12, 19 | Failed jobs, step errors, retry, e-mail log | As expected | Pass | E2E 12, 19 |
| F-46 | Loan-system sync | E2E 32 | Sync runs and records changes | As expected | Pass | E2E 32 |
| F-47 | Settings (admin only) | E2E 13 | Only admin sees Settings | As expected | Pass | E2E 13 |
| F-48 | Read-only roles cannot change a loan | roles.spec:125 | No change buttons for auditor and support | As expected | Pass | roles.spec |
| F-49 | Daily run and e-mails | OPS-02, OPS-04 | Runs at 06:00 IST, e-mails sent | As expected | Pass | OPS-02, OPS-04 |
| F-50 | Manual daily run | OPS-05 | Starts and reports completion | 504 at the gateway | **Fail** | BUG-10 |
| F-51 | Per-covenant schedules | OPS-06 | Spread out, in IST | Same second; 2,800 in UTC | **Fail** | BUG-08, BUG-09 |
| F-52 | Core without DPS | RES-01 | Core stays up | Stays up; staged values used | Partial | GAP-03 |
| F-53 | Backup and restore | OPS-01 | Identical data and files | Identical | Pass | OPS-01 |
| F-54 | Two Core nodes | OPS-03 | One fire per trigger | As expected | Pass | OPS-03 |
| F-55 | Accessibility (WCAG 2.1 AA) | axe in light and dark for 4 roles; screen-reader structure; phone width | 0 violations | 0 violations | Pass | a11y.spec |
| F-56 | Security controls | SEC-01..SEC-08 | Refusals and headers as specified | As expected | Pass | SEC tests |
| F-57 | Entra ID, bank gateway, bank services | n/a | Real integrations work | Only stubs in this environment | Not verified | GAP-07 |
