# Test results

Environment: one Windows 11 machine with 8 CPUs and 8 GB given to Docker. Everything ran on it: Core, DPS API and two workers, the UI on nginx, Keycloak, SQL Server 2022, WireMock bank stubs, and the load generator. Azure OpenAI was used for the AI tests. Dates are 3–5 November 2026, IST.

Scripts: `loadtest/users.py`, `loadtest/seed.py` and `loadtest/scale.py` are in the repository. The probe scripts named below were one-off review scripts. Raw JSON results are saved next to this report in `evidence/`.

## 1. Automated suites (baseline, unchanged code)

| Suite | Result |
|---|---|
| Core `mvn verify` (1,202 tests, JaCoCo 95% line / 90% branch gate) | Pass, 96% / 90% |
| Core SpotBugs | Pass, 0 findings |
| DPS pytest with coverage gate 90% | Pass, 98.08% |
| Golden-set gate | Pass, 24 of 24 (labels are extractor drafts; see GAP-01) |
| DPS ruff | Pass |
| UI unit tests (Vitest) | Pass, 122 of 122 |
| Playwright end-to-end (52 tests) | 50 passed, 2 failed in `roles.spec.ts:142` while load data was being written; re-run of `roles.spec.ts`: 11 of 11 passed |
| Accessibility (axe, light and dark, 4 roles, WCAG 2.1 AA rules) | Pass, 0 violations |
| Dependency scans (npm audit, pip-audit, Trivy images) | Pass, with 4 documented exceptions in `.trivyignore` |

## 2. Operations

| ID | Test | Result |
|---|---|---|
| OPS-01 | Backup and restore drill | Pass: 46,774 Core rows and 3,874 DPS rows identical after restore; 195 stored files with matching SHA-256 |
| OPS-02 | Nightly run (06:00 IST) and e-mail hand-off | Pass |
| OPS-03 | Two Core nodes with clustered Quartz | Pass: each trigger fired once; no duplicate tasks |
| OPS-04 | Daily sweep at scale | Pass: 7,612 covenants assessed in about 5 minutes (PRD: 10,000 in ≤ 30 minutes). 1 notification failed cleanly: 700 old load-test CPs have no bank stub. |
| OPS-05 | Manual sweep through the gateway | **Fail**: 504 after the proxy timeout, though the run completed (BUG-10) |
| OPS-06 | Schedules at 02:00 | **Fail**: 4,800 triggers fired in one second; backlog of 17.5 minutes (BUG-08). 2,800 triggers still in UTC (BUG-09). |

## 3. PRD performance targets (single user, idle system, DPS on SQL Server)

| ID | Target | Runs | Result |
|---|---|---|---|
| PERF-01 | Flow A, 50-page text agreement, p95 ≤ 90 s | 3 | Pass: 8.7 s, 4.3 s, 5.3 s, all PUBLISHED |
| PERF-02 | Flow B, statement, p95 ≤ 60 s | 5 | Pass: 1.9 s to 3.2 s, all PUBLISHED |
| PERF-03 | FR-09: re-assessment within 5 minutes of a statement | 5 | Pass: data-event assessments 0.2 s to 1.9 s after publish |
| PERF-04 | C2 values API p95 ≤ 500 ms | 200 calls | Pass: p50 52 ms, p95 83 ms, max 137 ms, all HTTP 200 |
| PERF-05 | Daily run, 10,000 covenants ≤ 30 minutes | 7,612 | Pass by extrapolation: about 5 minutes (OPS-04) |
| PERF-06 | Units | 1 | Pass: statement in INR lakhs returned in crore (EBITDA 514 lakhs = 5.14; debt 300 + 700 lakhs = 10.0) |

## 4. Load tests

The load mix is about 85% reads and 15% writes: portfolio summary, CP list, review grid, status history, tasks, schedule, library, documents, alerts, analytics, Excel export, waiver request and withdrawal, task note, CSV upload. There is no Ask AI in the mix. Virtual users sign in as 40 or 50 Keycloak test accounts, half RM and half approver.

Important: in runs LOAD-01 to LOAD-06 the load-test RMs' portfolio lists were empty, because their CPs were missing from Core's CP cache (BUG-06). Per-CP pages used real data, but summary, CP list and tasks returned empty lists. Those runs overstate capacity for portfolio pages. LOAD-09 repeats the test with real 60-CP portfolios.

| ID | Run | Users | Pauses | Throughput | Errors | Notes |
|---|---|---|---|---|---|---|
| LOAD-01 | Stress steps, pool 10 | 10 / 25 / 50 / 100 / 150 / 200 | none | 101 / 118 / 76 / 41 / 95 / 92 req/s | 0 / 0 / 0 / 0.95% / 0.15% / 1.39% | Pool exhausted from 50 users (10 active, 36–184 waiting). Errors: DPS 404 from access-check time-outs (BUG-07), dropped connections at 200 |
| LOAD-02 | Stress, pool 20 (env override) | 25 / 50 / 100 | none | 104 / 85 / 68 req/s | 0.06% / 0.23% / 0.04% | 1 × SQL 8003 on tasks (BUG-05) |
| LOAD-03 | Stress, pool 20, during the 02:00 burst | 50 / 100 | none | 27 / 33 req/s | 0.37% / 2.81% | Contaminated by BUG-08; shows its effect on users |
| LOAD-04 | Realistic, pool 20 | 100 / 250 / 500 | 3–8 s | 18 / 45 / 64 req/s | 0 / 0.39% / 4.50% | p95 under 100 ms at 100 users and under 400 ms at 250. Errors are dropped connections during token-refresh bursts on the shared host (RISK-03) |
| LOAD-05 | Soak, pool 20 | 200 | 3–8 s | n/a | 0.51% | The PC went to sleep 02:49–06:42; 29 minutes of valid samples. Core memory flat at 1.0 GiB, heap 430–675 MB, no pool waits, no growth. Errors: tokens expired during sleep, dropped connections, 7 × SQLite "database is locked" on upload |
| LOAD-06 | DPS on SQL Server, no RCSI | 50 / 200 | none / 3–8 s | 55 / 34 req/s | 0.93% / 0.44% | 63 of 121 and 41 of 188 uploads failed, deadlock 1205 (BUG-04) |
| LOAD-06b | DPS on SQL Server, RCSI on | 50 / 200 | none / 3–8 s | 52 / 32 req/s | 1.26% / 1.78% | 79 of 113 and 40 of 179 uploads failed, deadlock 1205 (BUG-04) |
| LOAD-07 | 02:00 burst, observed | n/a | n/a | n/a | n/a | 4,800 scheduled assessments 02:00:00–02:17:27, Core 400–500% CPU (BUG-08) |
| LOAD-08 | Bank-wide pages at 2,225 CPs | admin, auditor | n/a | n/a | 8 of 8 key pages HTTP 500 | SQL 8003 (BUG-02). At 25 CPs the same pages return 200 |
| LOAD-09 | **Real portfolios (60 CPs per RM), pool 10** | 25 / 50 (none); 100 / 250 (3–8 s) | as shown | 1.8 / 2.7 / 3.4 / 6.0 req/s | 10.8% / 21.8% / 21.4% / 58.5% | Open-breaches p95 66 s (504), tasks p50 34 s, Core 500–670% CPU, Hikari "waiting=192" (BUG-03) |
| LOAD-10 | Single user, 60 vs 12 CPs | 1 | n/a | n/a | 0 | Open-breaches 4–6 s vs 0.6–0.8 s; alerts 1.7–4.4 s vs 0.7–0.9 s; first tasks call 14.9 s vs 1.3 s (BUG-03) |
| LOAD-11 | Gateway, 300 threads, cheap endpoint | 300 | 0.5–2 s | 6,477 requests in 60 s | 0 | nginx and Core accept 300 concurrent connections without drops |

Capacity statement: on this host, with real portfolios, Core overloads at about 25 concurrent RMs. The earlier figures (117 req/s, 250 users at p95 under 400 ms) hold only for pages that do not list a portfolio.

## 5. Documents and files

| ID | Case | Expected | Result |
|---|---|---|---|
| DOC-01 | Encrypted PDF | Fails with a reason | Pass for safety (FAILED in 3 s, "Unreadable PDF: FileNotDecryptedError" on the job). The RM sees no reason (BUG-13) |
| DOC-02 | Truncated PDF | Fails cleanly | Pass, FAILED in 3 s, no internals leaked |
| DOC-03 | Corrupt .xls statement | Accepted type, fails cleanly | Pass, FAILED in 40 s |
| DOC-04 | Corrupt .xlsx statement | Fails cleanly | Pass, FAILED in 3 s |
| DOC-05 | 600-page PDF | Refused (limit 500) | **Fail**: accepted, `pageCount` 600 (BUG-11) |
| DOC-06 | Statement without asOfDate | 400 | Pass, 400 `AS_OF_DATE_REQUIRED` |
| DOC-07 | Same file twice | Same document | Pass, same `documentId` |
| DOC-08 | .exe, empty file, text named .pdf, 60 MB file | Refused | Pass (earlier probe): 400, 400, 400 `NOT_A_PDF`, 413 |
| DOC-09 | Path traversal in file name | Neutralised | Pass |
| DOC-10 | Upload to another RM's CP | Refused | Pass, 404 |
| DOC-11 | Blurred scan | Heavy OCR pool, held for review | Pass: HEAVY pool, Docling 60 s, NEEDS_REVIEW |

## 6. AI and extraction

| ID | Test | Result |
|---|---|---|
| AI-01 | Citation check over the 10 golden agreements | Pass: 0 failures (quote on the cited page and containing the threshold) |
| AI-02 | Coverage over the 10 golden agreements | Partial: numeric non-ratio clauses missed (RISK-01) |
| AI-03 | Prompt injection planted in an agreement | Extraction: Pass (1.35, 3.25 and 500 kept; the planted 0.10 and 99 ignored). Agreement Q&A: **Fail**, returned the planted sentence as the DSCR answer (BUG-12) |
| AI-04 | Question not answered by the document | **Fail**: returned an unrelated paragraph instead of "not stated" (BUG-12) |
| AI-05 | Repeatability, same question three times | Pass for consistency: identical answers (wrong ones, see AI-03) |
| AI-06 | Ask AI: portfolio question, other RM's CP, off-topic question | Pass: breaches listed with record citations (`breach:ac000000-…-0007`, `-0018`, `-0025`); CP-1008 "no entry"; weather refused |
| AI-07 | Operator wording | **Fail**: "shall not be less than 1.35" gives `<`; "not less than 1.35" gives `>=` (BUG-01) |
| AI-08 | Calculation spot check | Pass: CP-1005 net leverage 3.95 ≤ 3.75 gives breached (source 3.9500 at 31 Mar 2026); CP-1002 interest cover 3.4 ≥ 4.00 gives breached (source 3.4000) |
| AI-09 | Advisory label (FR-15) | Pass |

## 7. Resilience

| ID | Test | Result |
|---|---|---|
| RES-01 | Core with DPS stopped | Pass with deviation: portfolio summary, CP review, assessment list and library all 200; DPS documents give a clean 502. Covenants on DPS measures (for example `DPS_TOTAL_DEBT / DPS_NET_WORTH <= 2.5`) were assessed from Core's staged copy, not as `DATA_SOURCE_FAILURE` (GAP-03) |
| RES-02 | DPS worker lease and heartbeat | Pass (unit and integration tests; 120 s lease, renewed every 40 s) |
| RES-03 | Duplicate data event | Pass: unique-key violation caught and returned as a duplicate |
| RES-04 | Rate limits | Pass: 20 asks and 30 uploads per user per minute |

## 8. Security (automated probes on the local stack)

| ID | Test | Result |
|---|---|---|
| SEC-01 | No token, forged token, expired token | Pass, 401 |
| SEC-02 | Role gates on admin, support and author endpoints | Pass, 403 |
| SEC-03 | CP isolation across RMs (Core and DPS) | Pass, 404 |
| SEC-04 | Borrower portal limited to own CPs | Pass |
| SEC-05 | Error bodies leak no stack traces | Pass |
| SEC-06 | Headers: CSP, nosniff, referrer policy, frame options; server version hidden | Pass |
| SEC-07 | CORS refuses a foreign origin | Pass |
| SEC-08 | Secrets: none in the repository; startup refuses default secrets | Pass |
| SEC-09 | Penetration test, ASVS L2 review | Not done |

## 9. Phase 1 re-test (after the fixes, same environment)

| ID | Test | Before | After |
|---|---|---|---|
| FIX-01 | Live upload: "DSCR shall not be less than 1.35", "Total Debt to EBITDA shall not be more than 3.50", "ICR shall at no time be lower than 2.00" | `<` 1.35 (inverted) | `>=` 1.35, `<=` 3.5, `>=` 2.0 |
| FIX-02 | Unit tests: 11 wordings plus the operator-direction check | n/a | Pass |
| FIX-03 | Bank-wide pages at 2,285 CPs (admin, auditor) | 8 of 8 pages HTTP 500 | All 200: summary 1.2–2.1 s, CP list 1.5–3.4 s, breach report 4.3 s, tasks 0.1–0.2 s, alerts < 0.05 s |
| FIX-04 | One RM with 60 CPs, idle system | Breach report 4–6 s, alerts 1.7–4.4 s, first tasks 14.9 s, CP list 0.9 s | Breach report 0.16–0.25 s, alerts 0.14–0.17 s, tasks 0.04 s (1.4 s first), CP list 0.07–0.10 s, summary 0.07 s |
| FIX-05 | Uploads to DPS on SQL Server under load | 22–70% deadlock 1205 | 235 uploads, 0 deadlocks, 0 HTTP 500 |
| FIX-06 | CPs applied by API (60 CPs, RM 26) | Not in the cache, invisible to the RM | All 60 cached with RM and approver; load-rm-26 sees 60 CPs |
| FIX-07 | Background task sync | n/a | 2,285 CPs synced in about 2 minutes after start-up, one transaction per CP |
| FIX-08 | Latest-assessment query, 240 covenants | 109–114 ms CPU (269 ms under load) | 8–12 ms with index V31 |
| FIX-09 | Core memory under load | Heap committed 4.7 GB; SQL Server logins timed out | Container limit 2 GB, heap 1.6 GB, peak 1.94 GiB at 250 users |

### Load with real 60-CP portfolios (pool 20, DPS on SQLite)

| Users | Pauses | Throughput | Errors | Notes |
|---|---|---|---|---|
| 100 | 3–8 s | 16.4 req/s | **0** | p50 0.07–0.46 s; p95: review 0.7 s, upload 0.7 s, summary 2.1 s, CP list 2.4 s, alerts 3.1 s, breaches 3.9 s, tasks 5.0 s |
| 250 | 3–8 s | 25.6 req/s | 2.74% | Status-0 drops and BUG-07 (DPS access-check time-outs, open). SQL Server at 540% CPU is the limit |

Same tests before phase 1: 100 users 21.4% errors, 250 users 58.5%.

### Suites after the fixes

| Suite | Result |
|---|---|
| Core `mvn verify` | 1,163 unit + 51 integration tests pass; coverage gate met; SpotBugs 0 |
| DPS pytest | All pass, 98.06% coverage; ruff clean; golden set 24 of 24 |
| Playwright end-to-end | 52 of 52 pass (8.1 min) |
