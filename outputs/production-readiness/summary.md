# Production-readiness review: summary

**Verdict after phases 1–3: READY WITH CONDITIONS**

| | |
|---|---|
| Baseline review (no code changed) | 5 November 2026, verdict NOT READY |
| Phase 1 fixes and re-test | same day |
| Scope | Covenexa (Core, DPS, UI) on the local Docker stack with real Keycloak, SQL Server and Azure OpenAI, and stubbed bank services |

The baseline found one critical bug and five high-severity bugs; all six are now fixed and re-tested. The app can go to a controlled pilot. It must not go to full production until the four conditions below are met.

## Conditions before full production

1. **Analyst-labelled golden set (GAP-01).** Credit analysts label at least 20 real agreements and 20 statements and the gate passes on their labels. Everything they need is ready: `golden-set/ANALYST-GUIDE.md`, the review page, and `predict.py` plus `evaluate.py --statuses VERIFIED`.
2. **Bank integrations (GAP-07).** Test against Entra ID, the bank gateway, and the bank's CP, loan, notification and workflow services. Only stubs exist in this environment.
3. **Security (RISK-04).** Complete a penetration test and an ASVS L2 review.
4. **Capacity on production hardware (RISK-02).** Repeat the real-portfolio load test on production-like hosts, with the load generator on its own machine. Size Core, SQL Server and the connection pool from it.

Phase 2 is also done: BUG-07 to BUG-13, escalation notifications (FR-31) and bank-service credentials (GAP-06) are fixed and checked live; see "Phase 2" below.

## What phase 1 fixed

| Bug | Before | After (re-tested) |
|---|---|---|
| BUG-01 Critical: "shall not be less than 1.35" extracted as `< 1.35` | Floors read as ceilings, with no review flag | `>= 1.35`, `<= 3.5` and `>= 2.0` extracted live. An operator that points the wrong way for its metric now goes to NEEDS_REVIEW. 11 new wording tests. |
| BUG-02 High: bank-wide pages fail above 2,100 CPs | 8 of 8 key pages returned HTTP 500 at 2,225 CPs | All return 200 at 2,285 CPs: summary 1.2–2.1 s, CP list 1.5–3.4 s, breach report 4.3 s, tasks 0.1–0.2 s |
| BUG-03 High: portfolio pages slow down with portfolio size | One RM with 60 CPs: breach report 4–6 s, alerts 1.7–4.4 s, first task list 15 s | Breach report 0.16–0.25 s, alerts 0.14–0.17 s, tasks 0.04 s, CP list 0.08 s |
| BUG-04 High: uploads deadlock on SQL Server | 22–70% of concurrent uploads failed (deadlock 1205) | 235 uploads under load, 0 deadlocks; a lock that is lost is retried once and otherwise returns 503 |
| BUG-05 High: "My tasks" returns 500 after a large daily run | SQL 8003 | Every id list is batched in groups of 500; no SQL 8003 under any load since |
| BUG-06 Medium: CPs applied by API missing from portfolios | 2,200 CPs invisible to their RMs | 60 CPs applied through the API appear at once, with their RM and approver |

Fixes for problems found during the re-test:

- **Bank-wide task sync was quadratic.** Bank-wide roles no longer sync inline. A clustered background job keeps all CPs' tasks current; it synced 2,285 CPs in about 2 minutes.
- **Core could take 75% of the host's memory.** It grew to 4.7 GB and starved SQL Server until logins timed out. Core now runs with a 2 GB container limit.
- **Failed CP-name lookups repeated on every request.** Misses are now remembered for 10 minutes.
- **The latest-assessment query was slow.** It cost 109–269 ms of CPU per call; the new covering index V31 brings it to 8–12 ms.
- **The measure catalog was rebuilt on every assessment.** It is now cached for 30 seconds and refreshed as soon as a measure changes.

### Load result with real 60-CP portfolios

One 8-CPU host runs every service.

| Users (3–8 s pauses) | Before phase 1 | After phase 1 |
|---|---|---|
| 100 | 3.4 req/s, 21.4% errors, breach report p95 66 s | 16.4 req/s, **0 errors**, p50 0.07–0.46 s, p95 0.7–5 s |
| 250 | 6.0 req/s, 58.5% errors | 25.6 req/s, 2.7% errors (dropped connections, and BUG-07 time-outs) |

At 250 users, SQL Server is the limit: portfolio aggregates are recomputed on every page. A short per-user cache of portfolio aggregates is planned for phase 2.

## Phase 2

| Item | Before | After (checked live) |
|---|---|---|
| BUG-07 DPS access check | Slow Core gave "CP not found" | 503 "retry"; grants cached 30 s |
| BUG-08, BUG-09 Schedules | 4,800 covenants fired at 02:00:00; 2,800 in UTC | 9,060 triggers spread over 3,304 times, all IST |
| BUG-10 Manual daily run | 504 at the gateway | 202 at once; finished after 311 s (9,052 covenants) |
| BUG-11 Page limit | 600 pages accepted | Refused with a clear message |
| BUG-12 Agreement Q&A | Planted text returned as the answer | Right clause, or "does not appear to state that" |
| BUG-13 Failure reason | "Failed" only | "This PDF is password-protected. Upload a copy without a password." |
| FR-31 Escalation | No notification | Daily digest to `COVENANT_ESCALATION_RECIPIENTS` |
| GAP-06 Bank-service credentials | None sent | OAuth client credentials or a fixed token, per service |
| Capacity | 100 users: p95 up to 5 s | 100 users: p95 0.15–2.3 s, SQL Server CPU down from 422% to 173% |

Above about 100–150 concurrent RMs on one 8-CPU host, Core's CPU is the limit; add a second Core instance for more.

## Phase 3

- **Code scanning in CI:** bandit (DPS) and semgrep, 0 findings; workflow actions pinned to commit SHAs.
- **AI spend:** tokens (and cost, once rates are set) recorded for every model call; support sees totals.
- **Exposure by sector (FR-25):** treemap of facility exposure shaded by breach rate.
- **DPS safety check:** it will not start on SQL Server without snapshot isolation.
- **Real e-mail tested:** through a local test bridge and Mailpit, 28 RM summaries and an escalation digest were delivered.
- **Golden set ready for analysts:** 20 agreements and 10 statements, a one-page analyst guide, and an extraction regression gate in CI. On its first run the new synthetic set found four extraction bugs, all fixed. The worst (BUG-18, critical) read statements stated in plain rupees as crore.

## Phase 4

- **DPS down (GAP-03, decided):** assessments use Core's stored figures while they are within each measure's max age and say "Data may be stale"; with no stored copy they error as before. Checked live with DPS stopped. The PRD now says the same.
- **BUG-22 (high, found while building this):** a failed source refresh made the assessment's database transaction roll back, so its error result could not be saved. Fixed and checked live.
- **Amendments (C7, BUG-23):** an amendment's covenant now replaces the earlier agreement's covenant in recommendations; untouched covenants stay. Checked live.
- **More covenants extracted (RISK-01):** security cover, collateral value and asset-disposal limits, always held for a reviewer.
- **Old Excel (.xls) statements** are read.
- **Double scheduled assessments (RISK-05):** a late trigger no longer re-assesses a covenant the daily sweep already covered.
- **Two Core nodes:** 250 users at 46 req/s with 0 errors on the same host (one node: 8% errors). Quartz runs clustered across the nodes.
- **Flaky golden-set test (BUG-24)** fixed: the review tool reset the connection instead of refusing.

## After the review (6 November 2026)

- **Deletes are audited (BUG-25):** removing a certificate, a due date, an evidence file or an exchange rate is recorded with who and when, in Support → Audit log.
- **Ask AI no longer shows "Gateway Time-out" (BUG-26):** a model answer slower than 60 s falls back to the rules answer.
- **Sign-in tokens (BUG-27):** 30 s of clock difference is allowed, and every refusal is logged with its reason.
- **Accessibility (BUG-28):** the loading spinner is announced correctly.
- **Statement PDFs are read as tables (BUG-29 to BUG-31):** a benchmark of 10 readers on 24 statement PDFs moved DPS from 48% to 99.1% of values right, scans included; see `outputs/benchmarks/statement-pdf-readers/`.
- **Browser tests are stable (RISK-06 closed):** on freshly reset demo data, 52 passed, 0 failed, 0 flaky.

## Still true from the baseline

- **PRD performance targets pass with wide margins.**

  | Target | Required | Measured |
  |---|---|---|
  | 50-page agreement | ≤ 90 s | 4–9 s |
  | Statement | ≤ 60 s | 2–3 s |
  | Re-assessment after new data | ≤ 5 min | ≤ 2 s |
  | Values API | p95 ≤ 500 ms | 83 ms |
  | Daily sweep | 10,000 covenants ≤ 30 min | 7,612 in about 5 min |

- **Calculations, units, security controls, Ask AI scoping, backup and restore, the nightly run and two-node Quartz** all pass.
- **Correction:** GAP-10 was wrong. Certificate reminders and saved reports already run inside the daily 06:00 job.

## Quality gates after phase 4

| Component | Result |
|---|---|
| Core | 1,183 unit and 54 integration tests pass, coverage gate met, SpotBugs 0 findings |
| DPS | Tests pass, 98.21% coverage, ruff clean |
| Golden set | 28 tests pass; synthetic gate passed (recall, precision, thresholds, values all 1.0) |
| UI | Playwright 52 of 52 after re-running 2 tests that hit connection resets at the host's Docker port proxy; 128 unit tests |

## Files

- `requirements-traceability.md`
- `feature-test-matrix.md`
- `bug-and-risk-findings.md`
- `test-results.md` (section 9 is the phase 1 re-test)
- `pen-test-local.md` (scoped local penetration test)
- `improvement-plan.md`
- `evidence/`
