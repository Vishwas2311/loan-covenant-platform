# Production-readiness review: summary

**Verdict: NOT READY**

Review date: 5 November 2026. Scope: Covenexa (Core, DPS, UI) on the local Docker stack with real Keycloak, SQL Server and Azure OpenAI, and stubbed bank services. No product code was changed during the review.

## Why not ready

One critical requirement fails and three high-severity failures appear at a real bank's size.

1. **Wrong covenant direction (Critical, BUG-01).** The extractor reads the common wording "shall not be less than 1.35" as `< 1.35`. A floor becomes a ceiling, so a compliant borrower shows as breached and the reverse. The citation check passes and confidence is 0.85, so only the RM's review stands in the way. The golden set cannot catch it: it never uses this wording, and its labels were drafted by the extractor itself.
2. **Bank-wide pages fail above 2,100 CPs (High, BUG-02).** With 2,225 CPs, admin and auditor get HTTP 500 on the portfolio, CP list, tasks and open-breaches report. SQL Server's 2,100-parameter limit is the cause. "My tasks" hits the same limit after a large daily run (BUG-05).
3. **Capacity with real portfolios (High, BUG-03).** One RM with 60 CPs waits 4–6 s for the breach report and 15 s for the first task list. With 25 such RMs at once, the database pool is exhausted and requests fail (10–58% errors, 66 s time-outs).
4. **Uploads deadlock on the production database (High, BUG-04).** On SQL Server, 22–70% of concurrent uploads fail with deadlock 1205. The cause is that DPS sends strings as NVARCHAR to VARCHAR columns. The local stack runs DPS on SQLite, so this never showed up in testing.

## What works well

- **PRD performance targets** (single user):

  | Target | Required | Measured |
  |---|---|---|
  | 50-page agreement (Flow A) | p95 ≤ 90 s | 4–9 s |
  | Statement (Flow B) | p95 ≤ 60 s | 2–3 s |
  | Re-assessment after new data | ≤ 5 min | 0.2–1.9 s |
  | Values API | p95 ≤ 500 ms | 83 ms |
  | Daily sweep | 10,000 covenants ≤ 30 min | 7,612 in about 5 min |

- **Assessments are correct** against source data (spot checks), units convert correctly (lakhs to crore), and missing data never produces a breach.
- **Security controls** behave as specified: authentication, role gates, CP isolation in Core and DPS, upload abuse cases, headers, CORS, no leaked internals.
- **Ask AI** stays inside the RM's portfolio, cites the records it used, and refuses other RMs' CPs and off-topic questions. A clause planted to manipulate the AI did not change extracted thresholds.
- **Operations**: backup and restore are byte-identical, the nightly run and e-mail hand-off work, a two-node Quartz cluster fires each trigger once, and Core stays up when DPS is down.
- **Quality gates**: Core 1,202 tests (96% / 90%), DPS 98%, UI 122 unit tests, 52 Playwright tests, and 0 accessibility violations in light and dark themes.

## Other findings

| Severity | Count | IDs |
|---|---|---|
| Medium | 7 | BUG-06 to BUG-12 |
| Low | 2 | BUG-13, BUG-14 |

Gaps against the PRD:

- **Analyst-labelled golden set**: 10 agreements and no statements, against a target of at least 20 of each.
- **Not built**: escalation notifications, bandit/semgrep in CI, LLM cost tracking.
- **Not verified**: Entra ID, the bank gateway and bank services, and the penetration test.

## Correction to earlier load figures

Earlier runs reported 117 req/s and 250 users under 400 ms. Those runs used load-test RMs whose portfolio lists were empty, because of BUG-06. They hold only for pages that do not list a portfolio. LOAD-09, with real 60-CP portfolios, is the figure to plan with.

## Path to READY WITH CONDITIONS

Phase 1 of `improvement-plan.md`:

1. Fix the operator wording and add an operator-sanity check.
2. Have analysts label the golden set.
3. Batch id lists.
4. Remove the N+1 queries and slow scans.
5. Fix the DPS bind types and re-test uploads on SQL Server.
6. Write the CP cache on onboarding.

Then repeat LOAD-08, LOAD-09 and LOAD-06 and review again.

## Files

- `requirements-traceability.md`: FR-01 to FR-32 and the non-functional requirements, with status and evidence.
- `feature-test-matrix.md`: 57 features with steps, expected and actual results, and status.
- `bug-and-risk-findings.md`: 14 bugs, 11 gaps, 6 risks, with locations and evidence.
- `test-results.md`: suites, operations, performance, load, documents, AI, resilience and security results.
- `improvement-plan.md`: three phases with proofs and sizes.
- `evidence/`: raw JSON from the load, performance, file, AI and resilience runs.
