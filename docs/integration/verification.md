# Verification Log: Integration Study

Date: 2026-11-02.

Both projects were copied to a scratchpad and run there. The original folders were not modified.

Toolchain was installed locally in the scratchpad (no system install):
- Eclipse Temurin JDK 17.0.20 (adoptium.net)
- Apache Maven 3.9.9 (apache.org, SHA-512 verified)
- npm packages via `npm ci`
- Python 3.12 venv with `requirements.txt`
- Separate Python 3.12 venv with `docling` + `pdfplumber` (parser comparison only)

## 1. Their app: covenant-monitoring

### 1.1 What was read
| Area | Coverage |
|---|---|
| Docs | `README`, `CLAUDE.md`, `CHANGELOG`, user-story index, 1,139-line functional/technical guide: full |
| Backend services, read line by line | `AssessmentService`, `StagingIngestionService`, `IngestionOrchestrationService`, `MeasureAggregationResolver` (core path), `AppliedCovenantService`, `OnboardingRecommendationService`, `MlThresholdService`, `ReviewService`, `AttachmentService`, `AiSuggestionService`, `BreachNotificationService`, `CpCycleService`, `MonitoringService` (scoping), `FormulaController`, `SupportTokenInterceptor`, `CurrentUserContext`, `DispositionRules`, `WebClientDataApiClient` |
| Contracts | All controllers (endpoint list), the request DTOs used for integration, `DataSource`/`DataSourceTable` entities, external-service DTOs, WireMock stubs |
| Data | Flyway migration list; demo seed of measures and covenant library |
| Frontend | Structure of 123 files; API client identity headers. Pages not read line by line |
| Not read line by line | Formula lexer/parser/type-checker internals, ops services, remaining entities/DTOs/converters, most React pages, 123 test files (they were executed instead) |

### 1.2 Executed checks
| Check | Result |
|---|---|
| `mvn -B -P no-owasp verify` (unit + integration tests, JaCoCo gate, SpotBugs/FindSecBugs) | **BUILD SUCCESS** in 14:55 min. **964 unit + 40 integration tests, 0 failures**. JaCoCo bundle **97.2% line / 89.2% branch**, gate met. **SpotBugs 0 findings** |
| OWASP dependency-check | Not run (needs NVD download; skipped via `no-owasp` profile) |
| Frontend `tsc --noEmit` | 0 errors |
| Frontend `eslint --max-warnings=0` | Clean |
| Frontend `vitest run` (full) | 418 / 435 passed in the first run. All 17 failures were 15 s timeouts or `findBy` waits while Maven ran in parallel. **Re-run of the 3 affected files alone: 50 / 50 passed.** Conclusion: timing-sensitive tests, not product defects |
| Backend started with `--spring.profiles.active=dev` (H2 + WireMock 9311) | Healthy (`/actuator/health` 200) |

### 1.3 Live integration probe (proves contract C2)
A 20-line stdlib stub implemented the proposed DPS values API on `127.0.0.1:9400`. It returned DSCR 1.40 at 2025-12-31 and 1.10 at 2026-03-31. Then, through their public REST API:

| Step | Call | Result |
|---|---|---|
| 1 | `POST /api/v1/data-sources` type REST, baseUrl stub, valuesPath `/dps/v1/values` | 200, created |
| 2 | `POST /data-sources/{id}/tables` CP_ID, COLUMNAR | 200 |
| 3 | `POST /measures` `DSCR_DI` LATEST NUMBER | 200 |
| 4 | `POST /covenants` "Minimum DSCR (DPS)", `DSCR_DI >= 1.25`, AUTO | 200 |
| 5 | `POST /formulas/thresholds`, then `/apply-thresholds` to 1.20 (simulating an extracted threshold) | Slot `{11..15, DSCR_DI, >=}`; rebuilt `DSCR_DI >= 1.20`, valid |
| 6 | `POST /credit-proposals/CP-1001/applied-covenants` with per-CP override | 200, Quartz cron `0 0 2 1 1,4,7,10 ?` |
| 7 | `POST /assessments/run` | Stub received `{"cpId":"CP-1001","keys":["DSCR_DI"],"to":"2026-11-02"}`. Result **BREACHED**, `evaluatedExpression "1.1 >= 1.20"`, per-comparison row, workflow case `case-dev-mock-1` |

Conclusion: **our side can feed their engine through their existing REST data-source contract with no change to their code.**

### 1.4 Security probes (dev profile, no gateway)
| Probe | Result | Severity |
|---|---|---|
| `GET /monitoring/cp/CP-1001/review` with no `X-User` | Returns full review grid | High (cross-portfolio read) |
| `GET /assessments?cpId=CP-1001` with no `X-User` | Returns assessments | High |
| `GET /monitoring/summary` with no `X-User` | `[]` (correctly fail-closed) | — |
| `PUT /monitoring/cp/CP-1001/covenants/{id}/status` WAIVER with no `X-User` | **Accepted**; audit `changedBy: system` | High |
| Same with EXTENSION `extensionDate: 2020-01-01` | **Accepted** (past date) | Medium |
| `/api/v1/ops/health/data-sources` with `X-Support-Token: dev-support-token` | **200**. The default from `application.yml` applies in every profile when the env var is unset. The same token unlocks `DELETE /ops/cps/{cpId}/data` | High (if deployed without env var) |

Caveat: in production these depend on a gateway that authenticates users and overwrites `X-User`. That gateway is not in the repository.

## 2. Our app: covenanttrackingphase1.2

| Check | Result |
|---|---|
| `pip install -r requirements.txt` (Python 3.12 venv) | Success |
| Smoke via FastAPI `TestClient` on an isolated data copy; `COVENANT_ENABLE_LLM_REPORT=0`; invalid API key (no LLM calls) | Ran |
| Loan extraction, 4 distinct sample PDFs | All `success`, 0.97–6.5 s each. Example `300_Loan_agreement_sample_1.pdf`: DEBT_TO_EQUITY ≤ 2.0, DSCR ≥ 1.5, ICR ≥ 2.5, CURRENT_RATIO ≥ 1.25, MIN_NET_WORTH ≥ 5000 (pages 34–35) + 6 non-financial covenants. Emaar/ARAMCO/369: DSCR ≥ 1.25, ICR ≥ 2.00, DEBT_TO_EBITDA ≤ 3.50, DEBT_TO_NET_WORTH ≤ 2.50, EBITDA_TO_EMI ≥ 1.20, each with page |
| F-04 XSS: `/loan-pdf-viewer?snippet=</script><script>alert(1)</script>` | **Payload reflected raw**, confirmed at runtime |
| F-03: upload `b"not a pdf"` as compliance certificate | **`has_certificate: true`**, confirmed at runtime |
| F-05: `GET /api/companies` without credentials | 200 |
| Extraction accuracy | Not measured (no labelled golden set). The values above look consistent with the clause text the tool cited, but this was not hand-checked against every PDF |

## 2b. UI walkthrough (2026-11-02)

| App | How | Result |
|---|---|---|
| Theirs | Backend dev profile + Vite UI (`localhost:9312`) opened in the browser | Seen live: Covenant Library (42 covenants), Covenant detail (eligibility, applied on 25 CPs, expression change log), Edit covenant (formula builder with functions, windows, sampling, live validation "2.0 >= 1.50 → true"), Measure Library (18 measures), Create measure, Data Sources (5), Register data source (REST and MSSQL forms), CP Onboarding lookup (CP-2010 ReNew Power, obligor OBL-RENEW). The walkthrough stopped when the browser pane switched to mobile layout; the remaining screens were inventoried from their 28 page/component files |
| Ours | `web.py` served on `127.0.0.1:8010` (LLM off); all views, fields and button handlers extracted from the HTML/JS | Views: Dashboard, Facility Repository, Master Covenant, Agent Audits, Companies, Add Company (company form, loan upload, financial upload, non-financial evidence, covenant master configuration, run analysis, report, cycle compare, news, chat). **UI-only / fake features found:** "Send E-mail" shows "Email sent" without sending (hard-coded recipient names); "Upload to Linked Facilities" is a dummy toggle; the covenant status dialog's "Compliant (Waived)" / "Extended" only changes the on-screen report and is not saved |

Full inventory and merge decisions: [07-feature-inventory.md](07-feature-inventory.md).

## 3. Rechecks for the Flows design (v2)

| Check | Result |
|---|---|
| CrewAI Flow API in installed `crewai==1.13.0` | `Flow`, `start`, `listen`, `router`, `or_`, `and_`, `persist`, `human_feedback`, `Flow.kickoff_async`, `Flow.plot` import successfully |
| `FlowPersistence` interface (for a SQL Server backend) | Methods: `init_db`, `save_state(flow_uuid, method_name, state_data)`, `load_state(flow_uuid)`, `save_pending_feedback`, `load_pending_feedback`, `clear_pending_feedback`. Default `SQLiteFlowPersistence(db_path)` |
| CrewAI Flows docs (docs.crewai.com/en/concepts/flows, fetched 2026-11-02) | Resume by `kickoff(inputs={"id": …})`; routers should declare labels (`Literal[...]` or `emit=[...]`); web-app behaviour of `human_feedback` points to a separate async-feedback guide. The design therefore uses a `needs_review` router branch plus persistence, not a blocking `human_feedback` |
| Core re-assess hooks | `POST /api/v1/ops/applied-covenants/{id}/reassess` and `POST /ops/cps/{cpId}/reingest` exist, both behind the support token. `IngestionOrchestrationService.recordSourceChange` has no public endpoint, so a new C5 endpoint is needed |
| Core `POST /api/v1/formulas/validate` | Request is `{expression, requireBoolean}` only; evaluates against built-in sample values (`FormulaEngine.buildSample`). It cannot preview a ratio with real values, so a dry-run endpoint would be new |
| Core AI recommender DTOs | `AiStatusRequest(cpId, certificateFileIds, covenants[appliedCovenantId, covenantName, covenantDescription, conditionExpression, computedValue, latestResult, attachmentFileIds])`; `AiStatusRecommendation(appliedCovenantId, recommendedStatus: CovenantDispositionStatus, rationale, confidence: Double)`. 04-api-contracts C3 uses these names |

## 4. Parser engine comparison (2026-11-02)

Setup:
- Separate scratchpad venv: `docling 2.145.0`, `pdfplumber 0.11.10`, `pypdf 6.10.2`.
- CPU only, no GPU.
- Unique sample agreements from `uploads/loan_agreements`, deduplicated by SHA-256 (10 unique of 105 files; 218 pages; only 1 page without a text layer).
- 7 of the 10 completed before the run was stopped; the remaining 3 were not needed for the decision.

| File | Pages | pypdf | pdfplumber | Docling | Tables (pdfplumber / Docling) | Decimal numbers differing (Docling vs pypdf) |
|---|---|---|---|---|---|---|
| 164 Daily Health | 6 | 0.79 s | 2.93 s | 128.9 s (includes model load) | 0 / 0 | none |
| 188 Emaar | 6 | 0.87 s | 3.01 s | 43.8 s | 0 / 0 | none |
| 193 Bluecart | 6 | 0.88 s | 1.92 s | 42.8 s | 0 / 0 | none |
| 196 Lulu | 6 | 0.90 s | 1.97 s | 42.6 s | 0 / 0 | none |
| 205 Emirates | 6 | 1.67 s | 2.12 s | 39.9 s | 0 / 0 | none |
| 279 Daily Health | 2 | 0.18 s | 0.47 s | 13.8 s | 0 / 0 | none |
| 281 Loan sample | 85 | 9.65 s | — | 523.6 s | 0 / 2 | none |

Metric: the set of `\d+\.\d{2}` numbers in each engine's text. This is a coarse check that threshold-style numbers survive extraction; it is **not** a full accuracy test.

Decision recorded in [03 §8](03-flows-design.md):
- **Primary:** pypdf + RapidOCR.
- **Tables:** pdfplumber.
- **Fallback:** Docling in a separate heavy worker pool.
- **Azure AI Document Intelligence: not used.**

## 5. Not done / limits
- No OWASP/NVD scan, no load test, no browser UI walkthrough of either app.
- Their AI/ML/workflow/notification/file services are WireMock mocks; real behaviour unknown.
- Our LLM narrative and CrewAI path were not exercised (deliberately offline).
- All effort and duration figures in the PRD are estimates.
