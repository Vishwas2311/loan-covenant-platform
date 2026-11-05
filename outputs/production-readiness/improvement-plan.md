# Improvement plan

Order: first what can produce a wrong decision, then what fails at a real bank's size, then the rest. Each item names its finding, the change, and the test that proves it. Sizes are rough: S is up to a day, M is 2–4 days, L is a week or more.

## Phase 1: must fix before go-live (done 5 November 2026, except item 2)

| # | Finding | Change | Proof | Size |
|---|---|---|---|---|
| 1 | BUG-01 operator inversion | Accept words between "not" and the comparison in `_WORD_THRESHOLDS`. Add an operator-sanity rule: floors (DSCR, interest cover, net worth) must be `>=`/`>`, ceilings (leverage, debt/EBITDA) `<=`/`<`, otherwise NEEDS_REVIEW. | Unit tests for "shall not be less than", "shall not fall below", "shall not be more than", "shall not exceed", "shall be at least"; new golden documents with these phrasings | S |
| 2 | GAP-01 golden set | Credit analysts label at least 20 agreements and 20 statements, including Indian bank wording; the gate then grades against human labels | Golden gate runs on analyst labels in CI | L (people) |
| 3 | BUG-02, BUG-05 parameter limit | Bank-wide reads query without an id list. Every `findBy…In` and `findAllById` takes ids in batches of 500. | Integration test with 2,500 CPs and 10,000 covenants: bank-wide pages and tasks return 200 | M |
| 4 | BUG-03 portfolio speed | Batch the N+1 lookups (schedules, assessments, measures, masters). Rewrite `findByCpIdInOrderByIdAsc` and `findLatestOutcomes` to seek, using a join on an id table or a covering index with `OPTION (RECOMPILE)`. Cache library data. | LOAD-09 again: 25 RMs with 60 CPs each, p95 under 2 s, 0 pool time-outs | M |
| 5 | BUG-04 upload deadlocks | Make DPS bind types match the VARCHAR columns, or move the columns to NVARCHAR. Retry once on 1205 and return 503 for lock failures. Run the DPS integration and load tests on SQL Server, not SQLite. | LOAD-06 again on SQL Server: 0 upload failures at 50 users | M |
| 6 | BUG-06 portfolio cache | Write the CP cache entry whenever covenants are applied; the loan sync adds missing CPs. | Test: covenants applied by API make the CP appear in the RM's portfolio and tasks | S |

Phase 1 status: items 1 and 3–6 are done and re-tested (`test-results.md` section 9). Problems found during the re-test were fixed as well: bank-wide task sync moved to a background job, a Core memory limit, the CP-name miss cache, and covering index V31. Item 2, the analyst-labelled golden set, needs credit analysts and remains a condition for production.

## Phase 2: fix before a bank-wide rollout (done 5 November 2026, except item 16)

| # | Finding | Change | Proof | Size |
|---|---|---|---|---|
| 7 | BUG-08, RISK-05 schedules | Decide one owner for scheduled assessment (the daily sweep is faster). If per-covenant triggers stay, add a hashed minute offset. | 5,000 daily covenants: no misfire storm; user p95 unchanged during the run | M |
| 8 | BUG-09 time zone | Rebuild triggers whose zone differs from `covenant.time-zone` at startup | Startup test with a UTC trigger | S |
| 9 | BUG-10 manual run | Asynchronous run: 202 with a run id, a status endpoint, and polling in the UI | Manual run with 7,600 covenants shows progress and completion | S |
| 10 | BUG-07 access check | 503 on Core time-out; cache positive answers for 30–60 s | Unit tests; LOAD-01 without 404s | S |
| 11 | BUG-12 agreement Q&A | Rank covenant clauses first; flag text addressed to the model; "not stated in this document" below a relevance floor | AI-03 and AI-04 pass | M |
| 12 | BUG-11 page limit | Refuse more than 500 pages at upload | DOC-05 passes | S |
| 13 | BUG-13 failure reason | Plain-language reason on the document for the RM | DOC-01 shows the reason | S |
| 14 | GAP-02 escalation | Send the FR-31 escalation through the notification service | Test: breach unhandled more than N days sends one notification | M |
| 15 | GAP-06 notification auth | Authenticate to the bank's notification service | Contract test with the bank's sandbox | S |
| 16 | GAP-03 DPS-down behaviour | Product decision: keep assessing on the staged copy within `max_age_days`, or mark `DATA_SOURCE_FAILURE`. Document it and test the chosen path. | RES-01 with an expired staged copy | S |

## Phase 3: hardening and evidence

| # | Finding | Change | Size |
|---|---|---|---|
| 16a | Capacity at 250+ users | Short per-user cache of portfolio aggregates (summary, CP list, breach report), dropped on change marks, so pages stop recomputing the same aggregates. | M |
| 17 | RISK-02 capacity (BUG-14 done) | After items 3–5, load-test on production-like hosts with the load generator on its own machine; set pool, CPU and memory sizes from it. | M |
| 18 | GAP-04 code scanning | Add bandit (DPS) and semgrep (all) to CI | S |
| 19 | GAP-05 LLM cost | Record prompt and completion tokens and cost per job and per Ask AI call | S |
| 20 | GAP-07 integrations | Test with Entra ID, the bank gateway and the bank's CP, loan, notification and workflow sandboxes | L |
| 21 | RISK-04 security | Penetration test and ASVS L2 review | L (external) |
| 22 | GAP-11 | DPS refuses to start on SQL Server without `READ_COMMITTED_SNAPSHOT` | S |
| 23 | GAP-08 | Treemap and exposure charts (GAP-10 withdrawn: reminders and saved reports already run in the daily job) | M |
| 24 | RISK-06 | Make `roles.spec.ts:142` wait on data rather than time | S |

## Re-review gate

Run the review again after phase 1. READY WITH CONDITIONS needs all of these:

- BUG-01 to BUG-06 closed, with the proofs above.
- LOAD-09 within target.
- Golden-set gate on analyst labels.
- Phase 2 items scheduled before the bank-wide rollout.

Phase 2 status: items 7–15 and 16a are done and checked live (section 10 of `test-results.md`). Item 16 (what Core does while DPS is down) remains a product decision: assessments keep using Core's staged copy within each measure's `max_age_days`, which is documented in the runbook.

Phase 3 status (5 November 2026): items 18 (bandit, semgrep), 19 (AI tokens and cost), 22 (DPS snapshot check),
23 (treemap and exposure) and 24 (flaky test) are done. The golden set is prepared for analysts (synthetic set,
prediction script, CI gate, guide); the analysts' labelling, bank integrations (20), penetration test (21) and the
production load test (17) remain.

Phase 4 status (5 November 2026): item 16 (DPS-down behaviour) is decided and done: stored figures with a
"Data may be stale" note, PRD updated. Item 7's remaining double assessments are gone (late triggers skip a
covenant already covered). Also done: more covenant kinds extracted (RISK-01), `.xls` statements, amendments
replacing earlier terms (C7), and a two-Core-node load test on one host. Remaining: analyst labelling (2), the
production load test (17), bank integrations (20) and the penetration test (21).
