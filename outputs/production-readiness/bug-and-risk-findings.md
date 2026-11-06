# Bugs and risks

Severity:

- **Critical**: can produce a wrong covenant decision or lose data, or is a security hole.
- **High**: a core flow fails for real users at a realistic size.
- **Medium**: degraded behaviour, a misleading message, or an operational risk with a workaround.
- **Low**: polish or a documentation gap.

Every bug below was reproduced, with evidence in `test-results.md`. No product code was changed during the baseline review. Phase 1 fixes were then made and re-tested; see "Phase 1 fixes" at the end and section 9 of `test-results.md`.

## Summary

| ID | Severity | Area | Title | Status |
|---|---|---|---|---|
| BUG-01 | Critical | Extraction | "shall not be less than" is extracted as `<` (direction inverted) | Fixed |
| BUG-02 | High | Core | Bank-wide pages return 500 above 2,100 CPs | Fixed |
| BUG-03 | High | Core | Portfolio pages slow down with portfolio size; 25 RMs with 60 CPs each overload Core | Fixed (100 users: 0 errors) |
| BUG-04 | High | DPS | Concurrent uploads deadlock on SQL Server (the production database) | Fixed |
| BUG-05 | High | Core | "My tasks" returns 500 after a large daily run (SQL 8003) | Fixed |
| BUG-06 | Medium | Core | A CP onboarded without the recommendation step is missing from its RM's portfolio | Fixed |
| BUG-07 | Medium | DPS | DPS answers "CP not found" when Core is only slow | Fixed (phase 2) |
| BUG-08 | Medium | Core | All covenant schedules fire in the same second | Fixed (phase 2) |
| BUG-09 | Medium | Core | 2,800 schedules still run in UTC | Fixed (phase 2) |
| BUG-10 | Medium | Core / UI | A manual daily run through the gateway ends with 504 | Fixed (phase 2) |
| BUG-11 | Medium | DPS | The 500-page limit (FR-01) is not enforced | Fixed (phase 2) |
| BUG-12 | Medium | AI | Agreement Q&A returns planted or irrelevant text as the answer | Fixed (phase 2) |
| BUG-13 | Low | DPS / UI | The RM sees "Failed" with no reason | Fixed (phase 2) |
| BUG-14 | Low | Config | The local stack uses a 10-connection database pool | Fixed (pool 20 in every profile) |
| BUG-15 | High | Core | Bank-wide task sync was quadratic (found in re-test) | Fixed |
| BUG-16 | High | Deploy | Core heap could take 75% of the host and starve SQL Server (found in re-test) | Fixed |
| BUG-17 | Medium | Core | Failed CP-name lookups repeated on every CP-list request (found in re-test) | Fixed |
| BUG-22 | High | Core | A failed source refresh marked the whole assessment transaction for rollback (found in phase 4) | Fixed (phase 4) |
| BUG-23 | Medium | DPS | An amendment's terms were offered next to the terms they replace (C7) | Fixed (phase 4) |
| BUG-24 | Low | Golden set | The review tool reset the connection instead of refusing a bad save | Fixed (phase 4) |

## Bugs

### BUG-01 · Critical: "shall not be less than" is extracted as `<`

- **Where**: `dps/src/dps/extraction/agreement.py:73-77` (`_WORD_THRESHOLDS`).
- **What happens**: the "minimum" rule matches only "not less than", "at least" and similar phrases with nothing in between. "shall not **be** less than 1.35" skips it and falls through to the bare `less than` rule, which returns `<`. The maximum side has the same hole: "shall not be more than 3.5" matches the bare `more than` rule and returns `>`.
- **Evidence** (AI-01, AI-07):

  | Clause text | Extracted |
  |---|---|
  | "Debt Service Coverage Ratio shall not be less than 1.35 times" | `DSCR < 1.35` |
  | "DSCR shall not be less than 1.20:1" | `DSCR < 1.2` |
  | "maintain a DSCR of not less than 1.35" | `DSCR >= 1.35` (correct) |

  The citation check passes because the quote does contain 1.35, and confidence is 0.85.
- **Impact**: if the RM accepts the proposal, the covenant tests the opposite condition. A compliant borrower shows as breached and a breaching one as compliant. The PRD lists "wrong extracted threshold applied" as a High-impact risk. The golden set never uses this wording, and its labels were drafted by the extractor, so the gate cannot catch it.
- **Fix**: allow optional words between "not" and the comparison ("not (be|fall|go)? (less|lower|below|more|greater|higher|above) than"). Add an operator-sanity check: a DSCR or interest-cover floor with `<`, or a leverage ceiling with `>`, goes to NEEDS_REVIEW. Add these phrasings to the golden set.

### BUG-02 · High: bank-wide pages return 500 above 2,100 CPs

- **Where**:
  - `MonitoringService.java:80` (`cacheRepo.findAllCpIds()` for ADMIN, AUDITOR, SUPPORT), passed into `:116`, `:118`, `:309` and `:320`.
  - `WorkQueueService.java:161`.
  - The open-breaches report.
- **Evidence** (LOAD-08): with 2,225 cached CPs, these return HTTP 500 for both admin-1 and auditor-1: `/api/v1/monitoring/summary`, `/api/v1/analytics/cps`, `/api/v1/tasks` and `/api/v1/reports/open-breaches`. All eight failures logged "The incoming request has too many parameters. The server supports a maximum of 2100 parameters." With 25 cached CPs the same calls return 200.
- **Impact**: in a bank with more than about 2,100 CPs, admins, auditors and support cannot open the portfolio, tasks or breach report.
- **Fix**: for bank-wide roles, query without an id list. Elsewhere, batch id lists in groups of 500.

### BUG-03 · High: portfolio pages slow down with portfolio size

- **Where**: portfolio-level reads in `MonitoringService`, `WorkQueueService`, `BreachReportService` and the alerts service.
- **Single-user evidence** (LOAD-10, idle system):

  | Endpoint | 12 CPs | 60 CPs |
  |---|---|---|
  | `/api/v1/reports/open-breaches` | 0.6–0.8 s | 4.0–6.0 s |
  | `/api/v1/alerts` | 0.7–0.9 s | 1.7–4.4 s |
  | `/api/v1/tasks` (first call) | 1.3 s | 14.9 s |
  | `/api/v1/analytics/cps` | 0.05–0.2 s | 0.8–1.0 s |

- **Load evidence** (LOAD-09):
  - **25 users, no pauses**: 1.8 req/s, 10.8% errors, open-breaches p95 66 s (504).
  - **100 users, 3–8 s pauses**: 21% errors.
  - **250 users, 3–8 s pauses**: 58% errors.
  - Hikari logged "Connection is not available, request timed out after 30000ms (total=10, active=10, idle=0, waiting=192)" 444 times.
- **Root cause** (SQL Server query stats):
  - `findByCpIdInOrderByIdAsc` over 60 CPs runs 405 ms of CPU per call, because it scans the table instead of seeking `ix_applied_cp`.
  - The ROW_NUMBER `findLatestOutcomes` query runs 419 ms per call over about 240 covenants.
  - Per-row lookups run 70,000 to 400,000 times in a 10-minute run (schedules 406,867, assessments 140,306, measures 92,677, covenant masters 71,856), which is an N+1 pattern.
- **Impact**: earlier load results (117 req/s, 250 users under 400 ms) came from load users whose portfolio lists were empty. With realistic portfolios, the system overloads at about 25 concurrent RMs on this host.
- **Fix**: fetch schedules, assessments, measures and masters in batches. Rewrite the two hot queries with a join on a temporary id table or a table-valued parameter, or with `OPTION (RECOMPILE)` and a covering index. Cache the library (masters, measures), which changes rarely. Re-measure.

### BUG-04 · High: concurrent uploads deadlock on SQL Server

- **Where**: `dps/src/dps/api.py:247-271` (upload), running on `mssql+pymssql` as the runbook sets for production.
- **Evidence** (LOAD-11): the local stack runs DPS on SQLite, which also fails under concurrency (7 × `sqlite3.OperationalError: database is locked`). After switching DPS to SQL Server, the failures were:

  | Run | Without READ_COMMITTED_SNAPSHOT | With READ_COMMITTED_SNAPSHOT |
  |---|---|---|
  | 50 users, no pauses | 63 of 121 uploads (52%) | 79 of 113 (70%) |
  | 200 users, 3–8 s pauses | 41 of 188 (22%) | 40 of 179 (22%) |

  All failures were deadlock 1205. The deadlock graph shows two `UPDATE dps_document SET latest_job_id=N'…' WHERE dps_document.id = N'…'`, each holding an X key lock on `ix_dps_document_sha256` and waiting for a U lock on the other's.
- **Root cause**: pymssql sends strings as NVARCHAR (`N'…'`) while the columns are VARCHAR. SQL Server converts every row and scans the index instead of seeking, so two uploads lock each other's rows. Core had the same problem and fixed it with `sendStringParametersAsUnicode=false`.
- **Fix**: make the bind type match the columns, either by declaring the SQLAlchemy columns as `Unicode`/NVARCHAR or by binding as VARCHAR. Retry once on 1205, and return 503 rather than 500 on a lock failure. Re-run the upload load on SQL Server in CI.

### BUG-05 · High: "My tasks" returns 500 after a large daily run

- **Where**: `WorkQueueService.java:143`, `appliedRepo.findAllById(changed)`.
- **Evidence**: after a daily run assessed 7,612 covenants, `GET /api/v1/tasks` returned 500 with SQL 8003 (too many parameters). Seen in the smoke, stress and pool-20 runs (log correlation id `3ac62653…`), and 27 more times in the full-portfolio run.
- **Fix**: batch `changed` in groups of 500, as `BreachReportService` does.

### BUG-06 · Medium: a CP onboarded without the recommendation step is missing from its RM's portfolio

- **Where**: RM and bank-wide portfolios come from `cov_credit_proposal_cache` (`MonitoringService.java:75-83`). Rows are written only by `CachedCovenantAttributesService.fetch`, called from the onboarding recommendation (`MlThresholdService:141`), and updated by the loan sync.
- **Evidence**: 2,200 CPs had active covenants applied through the API, and none was in the cache. Their RMs (for example load-rm-03) saw 0 CPs in summary, CP list and tasks, yet the CP pages opened and assessments ran.
- **Impact**: any import or API onboarding that skips the recommendation call leaves covenants that run, breach and e-mail without appearing in anyone's portfolio or task list.
- **Fix**: write the cache entry whenever covenants are applied, and have the loan sync add missing CPs.

### BUG-07 · Medium: DPS answers "CP not found" when Core is only slow

- **Where**: `dps/src/dps/access.py:34-54`.
- **Evidence**: 112 times `Core ownership check failed for LT-…: ReadTimeout`, each followed by 404 CP_NOT_FOUND to the CP's own RM (stress run, 100 and 200 users).
- **Fix**: return 503 `ACCESS_CHECK_UNAVAILABLE` on timeout or connection error, and keep 404 for a real "no". Cache positive answers per (user, CP) for 30–60 s.

### BUG-08 · Medium: all covenant schedules fire in the same second

- **Where**: `enums/Frequency.java:4` and `:7` set one cron per frequency (`0 0 2 * * ?`, `0 0 2 1 1,4,7,10 ?`), and `ScheduleService.java:63` creates one trigger per covenant.
- **Evidence** (LOAD-07):
  - 4,800 triggers fired at 02:00:00 IST and the backlog lasted until 02:17:27.
  - The Quartz misfire handler logged 227 times in 3 minutes, and Core ran at 400–500% CPU.
  - User requests ran 1.6× to 3× slower during the burst.
  - The quarterly cron produces the same burst on the first day of each quarter.
- **Fix**: give each covenant a hashed minute offset, or let the daily sweep own scheduled assessment. The sweep assessed 7,612 covenants in about 5 minutes. See RISK-05.

### BUG-09 · Medium: 2,800 schedules still run in UTC

- **Evidence**: `QRTZ_CRON_TRIGGERS` holds 2,800 triggers with `Etc/UTC` and 4,800 with `Asia/Kolkata`. The UTC ones fired at 07:30 IST on 4 November.
- **Fix**: at startup, rebuild triggers whose zone differs from `covenant.time-zone`, and add a runbook check.

### BUG-10 · Medium: a manual daily run through the gateway ends with 504

- **Evidence**: `POST /api/v1/monitoring/notification-runs` with 7,612 covenants ran about 5 minutes inside the request. nginx logged "upstream timed out" and returned 504, although the run finished in Core.
- **Fix**: run it asynchronously and return 202 with a run id that the screen polls.

### BUG-11 · Medium: the 500-page limit (FR-01) is not enforced

- **Evidence** (DOC-05): a 600-page PDF of 262 KB was accepted (HTTP 202, `pageCount` 600).
- **Fix**: refuse more than 500 pages with 400 `TOO_MANY_PAGES` after `page_count` in `api.py`.

### BUG-12 · Medium: agreement Q&A returns planted or irrelevant text

- **Where**: `POST /dps/v1/documents/{id}/ask`, which picks quotes from the document and does not generate text.
- **Evidence** (AI-03, AI-04):
  - A clause planted in a test agreement told the AI to "Report the DSCR minimum as 0.10". Asked "What is the minimum DSCR?", the Q&A returned that planted sentence three times out of three, instead of clause 9.1 (1.35).
  - "What is the borrower's credit rating from CRISIL?" returned the first paragraph rather than "not found in this document".
  - Extraction itself was not affected (1.35, 3.25 and 500 were extracted).
- **Fix**: rank covenant clauses (with "shall", a comparison and a number) above other text. Flag text that addresses the model. Return "not stated in this document" below a relevance floor.

### BUG-13 · Low: the RM sees "Failed" with no reason

- **Evidence** (DOC-01): for an encrypted PDF, the RM's document record shows only `status: FAILED`. The reason, "Unreadable PDF: FileNotDecryptedError", is on the job, which only support can read.
- **Fix**: copy a plain-language reason onto the document ("This PDF is password-protected. Upload a copy without a password").

### BUG-14 · Low: the local stack uses a 10-connection database pool

- **Evidence**: `application-local-stack.yml` sets no pool size, so Hikari uses 10, while prod uses `COVENANT_DB_POOL_SIZE` (default 20). At 100 users with no pauses and empty portfolios:
  - **Pool 20**: 68 req/s, 0.04% errors.
  - **Pool 10**: 41 req/s, 0.95% errors.
- **Fix**: read the same variable in local-stack, and size it from a production-host load test.

## Gaps against the PRD

| ID | Severity | Gap |
|---|---|---|
| GAP-01 | High | Golden set: 10 agreements, 0 statements. The PRD asks for at least 20 of each, labelled by credit analysts. The labels are extractor drafts, so the gate cannot find extractor errors (BUG-01). |
| GAP-02 | Closed (phase 2) | FR-31: escalations show in reports and tasks, but no notification is sent. Now a daily digest goes to `COVENANT_ESCALATION_RECIPIENTS`. |
| GAP-03 | Closed (phase 4) | Reliability wording: with DPS down, assessments run on Core's staged copy (`cov_staging_value`), within `max_age_days`, instead of failing with `DATA_SOURCE_FAILURE`. Core stays up; the stale-copy path needs a decision and a test. |
| GAP-04 | Closed (phase 3) | Quality gate: bandit (DPS, every severity) and semgrep (`p/default`) now run in CI; GitHub Actions pinned to commit SHAs. |
| GAP-05 | Closed (phase 3) | Every model call's tokens are stored per request (`dps_ai_usage`); cost when the bank sets its rates; `GET /dps/v1/ai-usage` for support. |
| GAP-06 | Closed (phase 2) | The notification client sent no authentication. Every bank-service client now uses OAuth client credentials (or a fixed token) when configured. |
| GAP-07 | Medium | Not verified: Entra ID SSO, the bank gateway, the bank's CP, loan, notification and workflow services (the workflow client logs "placeholder for case case-dev-mock-1"). |
| GAP-08 | Closed (phase 3) | FR-25: exposure by sector (₹ crore, from the CP service's facility amount) as a treemap shaded by breach rate. |
| GAP-09 | Low | FR-28 financial news not built (optional). |
| GAP-10 | n/a | Withdrawn: certificate reminders and saved reports already run inside the daily 06:00 job (`DailyBreachRunJob`). |
| GAP-11 | Closed (phase 3) | DPS refuses to start on SQL Server without `READ_COMMITTED_SNAPSHOT`, printing the statement for a DBA. |

## Risks

### RISK-01 · High: extraction recall is unproven

Over the 10 golden agreements:

- **Citations**: 0 failures; every quote is on its page and contains its threshold.
- **Coverage**: some numeric clauses produced no term:
  - collateral value "not less than INR 10,000 Crores" (AGR-007 and AGR-008, p.15);
  - substitute collateral "at least 120%" (p.67);
  - asset sales "not exceeding INR 50 Crores in any Financial Year" (p.33).
- **Total Debt to EBITDA**: AGR-007 states the 3.5:1 limit only in its compliance-certificate template (p.55), and it was extracted. AGR-008 leaves that limit blank, so leaving it unextracted is correct.

A credit analyst must decide which clauses are covenants.

Phase 4: these three kinds of clause are now extracted (minimum security cover, minimum collateral value, asset-disposal limit) and always held for a reviewer, because whether such a clause is an ongoing covenant is a credit judgement. Recall stays unproven until analysts label real documents (GAP-01).

### RISK-02 · High: capacity is not established

With realistic portfolios (60 CPs per RM), this single 8-CPU host overloads at about 25 concurrent RMs (BUG-03). The load generator ran on the same host. Repeat the test on production-like hosts after BUG-03 and BUG-04 are fixed.

Phase 4: two Core nodes behind the gateway on the same host carried 250 users (3–8 s pauses) at 46 requests/s with 0 errors; one node gave 18.4 requests/s and 8% errors. A test on production hosts is still needed.

### RISK-03 · Medium: token-refresh bursts

When 500 sessions shared 40 test accounts, token renewals pushed Keycloak to 277–384% CPU, followed by dropped connections on the shared host. Entra ID changes this in production, but gateway token validation should be load-tested there.

### RISK-04 · Medium: no penetration test and no ASVS L2 review

Both are required by the PRD before go-live.

### RISK-05 · Low: two schedulers assess the same covenants

Per-covenant triggers and the daily sweep both write SCHEDULED assessments. 7,600 covenants had two on one day; part of that came from sweeps started by hand during this review.

Closed in phase 4. The sweep already skipped a covenant checked since its due time. The remaining double came from a trigger that fired after the sweep had covered its slot (a late or misfired trigger, or a sweep started by hand). The trigger now checks first and skips when the covenant was assessed at or after the trigger's scheduled time.

### RISK-06 · Low: a role test is flaky under load

`roles.spec.ts:142` failed during the baseline run while load data was being written, and passed on re-run (11 of 11).

## Phase 1 fixes

| Bug | Change | Where |
|---|---|---|
| BUG-01 | "not", "never" or "at no time", plus an optional verb (be, fall, go, drop, rise), before less/lower/below or more/greater/higher/above. New operator-direction check: a floor metric with `<`/`<=` or a ceiling metric with `>`/`>=` gets `OPERATOR_UNEXPECTED` and NEEDS_REVIEW. | `dps/src/dps/extraction/agreement.py`, `validation.py`; 13 new tests |
| BUG-02, BUG-05 | `IdBatches.fetch` runs every `IN (...)` query in slices of 500, at all call sites that take portfolio-sized lists. Task lists and saved reports use `visibleCpIds` instead of building the whole summary. | `util/IdBatches.java`; Monitoring, WorkQueue, Analytics, CpDirectory, ScheduledReport, BreachNotification, IngestionPreStage and DataEvent services |
| BUG-03 | Schedules and latest assessments loaded in batches, not per covenant. Bulk `covenantsForCps` and `reviewRowsFor` for the breach report, alerts and task sync. Hibernate IN-list padding, so plans are reused. Covering index V31 for the latest assessment. Measure catalog cached for 30 s, refreshed on change. | `MonitoringService`, `ReviewService`, `BreachReportService`, `AlertService`, `MeasureCatalogService`, `MeasureChangeListener`, `V31__assessment_latest_covering_index.sql`, `application.yml` |
| BUG-04 | DPS `Varchar` column type casts bound strings to VARCHAR (pymssql sent NVARCHAR). The upload retries once on a lost lock; any remaining lock conflict returns 503 `BUSY_RETRY` with `Retry-After`. | `dps/src/dps/models.py`, `api.py`, `errors.py`; 4 new tests |
| BUG-06 | After covenants are applied, the CP is written to the CP cache (owners from the CP service); a failure is logged, never thrown. | `AppliedCovenantController`, `CachedCovenantAttributesService.ensureCached` |
| BUG-14 | `application-local-stack.yml` reads `COVENANT_DB_POOL_SIZE` (default 20) like prod. | config |
| BUG-15 | Reads sync inline only for portfolios up to 300 CPs, clearing the persistence context after each CP. `TaskSyncJob` (clustered Quartz, every 5 minutes) syncs every CP in its own transaction. | `WorkQueueService`, `TaskSyncJob`, `ScheduleService`, `DailyRunInitializer`, `TaskSyncTracker` |
| BUG-16 | `mem_limit: ${CORE_MEM_LIMIT:-2g}` on Core, so `MaxRAMPercentage=75` applies to 2 GB (heap 1.6 GB). | `deploy/docker-compose.yml` |
| BUG-17 | CP-name misses remembered for 10 minutes. | `CpDirectoryService` |

## Phase 2 fixes

| Item | Change | Where | Live check |
|---|---|---|---|
| BUG-07 | Core time-out, connection error or 5xx gives 503 `ACCESS_CHECK_UNAVAILABLE`, not 404. A "yes" is cached for 30 s per (user, roles, CP, read/write), so DPS no longer asks Core on every call. | `dps/src/dps/access.py` | Load test, section 10 of `test-results.md` |
| BUG-08 | A frequency's default cron gets a second and minute within its hour from the covenant id (`ScheduleService.spread`). | `ScheduleService` | 9,060 triggers spread over 3,304 distinct times |
| BUG-09 | At start-up, triggers still bunched or in another zone are rebuilt in the server zone, in the background. | `ScheduleService.realignCovenantTriggers`, `DailyRunInitializer` | "Realigned 9057 covenant triggers"; all 9,060 on Asia/Kolkata |
| BUG-10 | The manual run starts in the background; POST answers 202 with a run id, GET reports RUNNING/DONE/FAILED from table `cov_manual_run` (V32), so any instance can answer; one run at a time. The Support screen follows the run. | `ManualRunService`, `NotificationRunController`, `Support.tsx` | 202 in 0.39 s; 9,052 covenants DONE after 311 s, no 504 |
| BUG-11 | PDFs over `DPS_MAX_PAGES` (500) refused with 400 `TOO_MANY_PAGES`. | `dps/src/dps/api.py` | 600 pages: 400 with "Split it and upload the parts" |
| BUG-12 | Q&A ranks covenant clauses first, matches metric aliases and min/max wording, drops text addressed to an AI model (also from the model's extracts), and answers "does not appear to state that" below a relevance floor. | `dps/src/dps/agreement_qa.py` | Planted "DSCR minimum 0.10" no longer answers; CRISIL rating question gets "not found" |
| BUG-13 | Failed documents carry `failureReason` in plain words; the document page shows it to the RM. | `dps/src/dps/api.py`, `Documents.tsx` | Encrypted PDF: "This PDF is password-protected. Upload a copy without a password." |
| GAP-02 | Daily escalation digest to the configured recipients, audited with the other notifications. | `EscalationService`, `DailyBreachRunJob` | Unit tests (needs the bank's template `Cov_Escalation`) |
| GAP-06 | OAuth client credentials or a fixed token for every bank-service client. | `WebClientsConfig.bankClient` | WireMock tests: token fetched and sent |
| Capacity | Portfolio summaries and review rows cached for up to 30 s, dropped on any write the change listener sees. | `PortfolioCache`, `MonitoringService`, `ReviewService` | Section 10 of `test-results.md` |

## Phase 3 fixes and golden-set findings

| Item | Change | Evidence |
|---|---|---|
| GAP-04 | `bandit` and `semgrep` jobs in `security.yml`; two bandit false positives marked `# nosec` with reasons; nginx upstream lines marked `nosemgrep` (the upstream comes from the environment, not the request); `.semgrepignore` leaves out local test and ops scripts. All workflow actions pinned to commit SHAs (17 findings). | bandit 0, semgrep 0 |
| GAP-05 | `dps/src/dps/usage.py`, table `dps_ai_usage` (Alembic `c4d8e2f1a7b3`), `GET /dps/v1/ai-usage`. One `LLM` instance per call so token counts belong to that call. | Live: one Ask AI call recorded 4,048 prompt and 166 completion tokens; RM gets 403 on the summary |
| GAP-08 | `AnalyticsService` adds `exposureCrore` per group; `Treemap` (squarified) in the chart kit; "Exposure by sector" card on Reports. | Screenshots light, dark and phone; 67 of 85 CPs carry a facility amount |
| GAP-11 | `require_snapshot_isolation` in `dps/src/dps/db.py`, called by the API and the workers. | Unit test |
| RISK-06 | `roles.spec.ts:142` waits for the CP count to settle instead of reading it once. | Playwright suite |
| E-mail | `deploy/docker-compose.mail.yml`: a test bridge turns Core's notification calls into real e-mails delivered to Mailpit. The manual run now also sends the escalation digest, as the daily job does. | Live: 28 RM summaries (RM in To, approver in Cc) and 1 escalation digest to `credit.head@covenant.local` |
| GAP-01 (prepared) | 10 synthetic agreements and 10 statements with labels written from the specification (`make_synthetic.py`); `predict.py` runs DPS's extractors; CI gate `evaluate.py --statuses SYNTHETIC`; statement scoring; `ANALYST-GUIDE.md`. Analyst labelling of real documents is still needed. | Synthetic gate: recall, precision, thresholds, pages and values all 100% |

The synthetic golden set found four extraction bugs on its first run, all fixed and covered by tests:

| ID | Severity | Bug | Fix |
|---|---|---|---|
| BUG-18 | Critical | A statement in plain rupees ("Amounts in INR") was read as crore: every amount 10,000,000 times too large | Plain-rupee wording recognised; when no unit is stated at all the statement goes to review with `UNIT_NOT_STATED` |
| BUG-19 | Medium | No TOTAL_DEBT when a statement has long-term but no short-term borrowings | Derived from the borrowings lines present |
| BUG-20 | Medium | "Net Debt to EBITDA" also produced a Debt to EBITDA covenant | A match inside a longer alias of another metric is skipped |
| BUG-21 | Low | Negative pledge missed when words sit between "shall not" and "create any lien" | Pattern allows up to 80 characters between them |

## Phase 4 fixes

| Item | Change | Where | Live check |
|---|---|---|---|
| GAP-03 (decision: use stored figures) | When a source cannot be read and Core holds a stored copy, the assessment uses it and carries the note "Data may be stale: the latest refresh of … failed, so these figures come from the stored copy of …". A value older than its measure's max age is still refused, and with no stored copy the assessment is `DATA_SOURCE_FAILURE` as before. A rejected configuration (bad identifier, host not allowed) still fails. PRD reliability line updated. | `AssessmentService.ingestStaging`, `IngestionOrchestrationService.storedCopyAt`, `SourceReadException`, `docs/integration/05-prd.md` | DPS stopped, CP-1001 forced to refresh: COMPLIANT with the note, ingestion state `FAILED`; DPS back: no note |
| BUG-22 | Found while building GAP-03. A source error passed through two `@Transactional` methods, which marked the caller's transaction rollback-only, so the assessment's own ERROR row and the `FAILED` ingestion status could not be saved. Source-read failures (nothing written yet) are now `SourceReadException`, which those methods do not roll back for. | `StagingIngestionService`, `IngestionOrchestrationService` | The live check above saved both the assessment and `FAILED` |
| RISK-05 | A per-covenant trigger skips the assessment when the covenant was already assessed at or after the trigger's scheduled time; the schedule row is still updated. | `AssessmentJob` | Unit test |
| RISK-01 | Security cover, collateral value and asset-disposal limits extracted, held for review; amounts in crore. Synthetic agreements AGR-013, -016 and -019 carry them; draft labels AGR-007 and -008 gained them for analysts to confirm. | `dps/src/dps/extraction/agreement.py`, `mapping.py`, `golden-set/` | Synthetic gate 100%; real drafts recall 1.0, precision 1.0 |
| `.xls` statements | Old Excel workbooks are read with cell references (`PL!B2`); a corrupt one fails with "Unreadable .xls statement". | `dps/src/dps/extraction/statement.py`, `flows/statement.py` | Unit tests |
| BUG-23 | Recommendations take an amendment's term in place of the earlier agreement's term for the same covenant, through any chain of amendments; covenants the amendment does not mention keep their terms. | `dps/src/dps/services.py` | Live: AGR-013 (DSCR ≥ 1.30) plus an amendment (DSCR ≥ 1.20) on CP-1001: one DSCR recommendation, 1.20 from the amendment; leverage and FCCR from the original |
| BUG-24 | A refused save is read before the answer, so Windows no longer resets the connection; the 422 test can no longer pass silently. | `golden-set/review.py`, `test_review.py` | 40 runs, 4 in parallel: 0 failures |
| Scale-out | `deploy/docker-compose.scale.yml` runs two Core nodes behind the gateway with Quartz clustered (as the prod profile does). | `deploy/` | Both schedulers report "is clustered"; load in `test-results.md` section 12 |

## Fixes after the review (6 November 2026)

| Item | Change | Where | Check |
|---|---|---|---|
| BUG-25 · Medium | Deleting a compliance certificate, a certificate due date, an evidence file or an exchange rate left no record of who did it. Each delete now writes a `cov_ops_audit` row (`DELETE_CERTIFICATE`, `DELETE_CERTIFICATE_DUE_DATE`, `DELETE_EVIDENCE`, `DELETE_FX_RATE`) with the user and time; Support → Audit log shows them, with a new **Exchange rates** choice. | `AttachmentService`, `CertificateRequirementService`, `FxService`, `ui/src/pages/Support.tsx` | Unit tests; Core verify and SpotBugs; CI |
| BUG-26 · Medium | Ask AI and agreement questions could wait on the model longer than the gateway's 120 s and show "Gateway Time-out". Each model call now stops after `DPS_AI_TIMEOUT_SECONDS` (60 s) and the rules answer is given instead. | `dps/src/dps/advisor.py`, `settings.py`, `main.py` | Browser test 26 on a loaded machine |
| BUG-27 · Low | DPS refused a token whose times were a second off the identity provider's clock, and did not say why. It now allows `DPS_JWT_LEEWAY_SECONDS` (30 s; Core allows 60) and logs the reason for a refusal, never the token. | `dps/src/dps/auth.py` | Unit tests for 10 s either way; expired by 120 s still refused |
| BUG-28 · Low | The loading spinner put `aria-label` on a span with no role (axe `aria-prohibited-attr`), seen when the accessibility scan caught a page still loading. It now has `role="status"`. | `ui/src/components/Status.tsx` | a11y browser tests |
| BUG-29 · High | Statement PDFs were read as plain text, taking the first number on each line: in a 24-document benchmark 48% of values were right and 52 wrong values would have been reported without review (note numbers, quarter or previous-year columns, narrative). pdfplumber, chosen for this in the design, had never been wired in. Now `extraction/statement_pdf.py` reads the table from word positions and picks the current annual standalone column. | `dps/src/dps/extraction/statement_pdf.py`, `flows/statement.py`, `worker.py` | Benchmark 99.1% (321 of 324 values; the 3 misses are on a blurred 100 dpi scan, held for review); golden-set gate; live upload through the worker |
| BUG-30 · High | Scanned statement PDFs gave no values: RapidOCR returns labels and amounts as separate lines. The reader now uses the OCR boxes' positions, straightens the page, and matches labels that lost their spaces; a scan read below 97% OCR confidence is held for review (`LOW_OCR_QUALITY`). | same | Clean scans 12 of 12; blurred scan flagged |
| BUG-31 · Low | Capex, principal repaid and dividends printed as outflows "(135.88)" were stored as negative. They are now stored as positive amounts. | `extraction/statement.py` | Unit test |
| BUG-32 · High | On real Indian and Sri Lankan reports the statement reader got units and columns wrong: "₹ Lakhs" extracted as "I Lakhs", "Rs.’000" and "Rs 000" read as rupees, the Group column taken over Company, a quarter over the year, the previous year when signature dates sat under it, note numbers as revenue. Fixed in the reader and the line rules; hand-checked accuracy went from 32% to 91.9% on the hardest documents. | `extraction/statement.py`, `statement_pdf.py` | `tests/test_real_documents.py`; benchmark on 47 real reports (`outputs/benchmarks/real-documents/`) |
| BUG-33 · Medium | A 300-page annual report was read whole, so a five-year summary or a notes page could supply a value first. Long PDFs are now searched for their statement pages and only those are read. | `extraction/statement_pages.py` | Right page in the first two for 96.4% of 137 real statements |
| BUG-34 · Medium | US credit agreements write covenants as "will not permit the Leverage Ratio to exceed 3.50" and use names the catalog lacked; pricing grids and incurrence tests were proposed as covenants. Names, wording and exclusions added. | `extraction/agreement.py` | Real-agreement benchmark (60 hand-labelled covenants); unit tests |
| RISK-07 (new, mitigated) | No reader is right on every real statement (best 91.9% India / Sri Lanka, 93.0% US by different engines). Optional second reader `DPS_STATEMENT_AI_CHECK`: the model reads the same pages and a disagreement holds the statement for review; on SEC filings wrong values that would be published fell from 9 to 4. | `extraction/statement_ai.py` | Unit tests; benchmark section 4.3 |
| RISK-06 (closed) | Browser tests timed out on a busy computer: each test left its extra sign-in sessions open, Docker sometimes reset a connection, and two tests relied on data added by hand. Sessions are closed after each test, sign-in retries the first page load, a failed test is tried once more (reported as flaky), and tests 15 and 16 set up their own data. | `ui/e2e/`, `ui/playwright.config.ts` | Straight after `scripts/reset-demo-data.sh`: 52 passed, 0 failed, 0 flaky, 6 screenshot-only skipped |

Note on C7 in Core: onboarding lists only covenants not yet applied to the CP. When an amendment changes a covenant the CP already has, the document page shows "Changes made by this amendment" and the RM updates the CP's own rule for that covenant (A3). Core does not change an applied covenant by itself.
