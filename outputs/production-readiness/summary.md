# Production-readiness review: summary

**Verdict after phase 1 fixes: READY WITH CONDITIONS**

| | |
|---|---|
| Baseline review (no code changed) | 5 November 2026, verdict NOT READY |
| Phase 1 fixes and re-test | same day |
| Scope | Covenexa (Core, DPS, UI) on the local Docker stack with real Keycloak, SQL Server and Azure OpenAI, and stubbed bank services |

The baseline found one critical bug and five high-severity bugs; all six are now fixed and re-tested. The app can go to a controlled pilot. It must not go to full production until the four conditions below are met.

## Conditions before full production

1. **Analyst-labelled golden set (GAP-01).** Credit analysts label at least 20 agreements and 20 statements, including Indian bank wording, and the golden-set gate passes on their labels. The extraction bug fixed in BUG-01 was invisible to the current gate, whose labels were drafted by the extractor itself.
2. **Bank integrations (GAP-07).** Test against Entra ID, the bank gateway, and the bank's CP, loan, notification and workflow services. Only stubs exist in this environment.
3. **Security (RISK-04).** Complete a penetration test and an ASVS L2 review.
4. **Capacity on production hardware (RISK-02).** Repeat the real-portfolio load test on production-like hosts, with the load generator on its own machine. Size Core, SQL Server and the connection pool from it.

Phase 2 items (BUG-07 to BUG-13 and the open gaps) should be scheduled before a bank-wide rollout.

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

## Quality gates after the fixes

| Component | Result |
|---|---|
| Core | 1,163 unit and 51 integration tests pass, coverage gate met, SpotBugs 0 findings |
| DPS | Tests pass, 98% coverage, golden-set gate 24 of 24, ruff clean |
| UI | Playwright 52 of 52 pass; 122 unit tests (unchanged) |

## Files

- `requirements-traceability.md`
- `feature-test-matrix.md`
- `bug-and-risk-findings.md`
- `test-results.md` (section 9 is the phase 1 re-test)
- `improvement-plan.md`
- `evidence/`
