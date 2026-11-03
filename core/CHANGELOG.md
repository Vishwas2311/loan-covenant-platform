# Changelog

## 0.1.0 — Initial release

### Backend

- **Covenant master library** (Sprint 1): CRUD endpoints, soft-delete via `active`, error envelope `{code, message, details, timestamp}`, H2 in MSSQL-compatibility mode, Swagger UI at `/swagger-ui.html`. JPA auditing via `X-User` gateway header.
- **SpEL rule authoring** (Sprint 2): rule expressions in Spring Expression Language with `#metric` and `#threshold_metric` variables. Defence-in-depth: denylist of dangerous tokens (`T(`, `new`, `.class`, `@`, `?.`, `?[`), `SimpleEvaluationContext.forReadOnlyDataBinding()` runtime, declared-variable check, boolean-result enforcement. `POST /api/v1/covenants/validate-expression` for interactive validation. Paginated search/filter (`Specification<CovenantMaster>`).
- **External integrations** (Sprint 3): Spring `WebClient` based `CreditProposalClient`, `CovenantAttributesClient`, `DataApiClient`, `WorkflowClient`. All base URLs externalised under `covenant.clients.*` (env-overrideable). WireMock stubs in `src/main/resources/wiremock/` for dev/CI.
- **CP onboarding & recommendation** (Sprint 3): `GET /api/v1/credit-proposals/{cpId}` lookup, `GET /api/v1/credit-proposals/{cpId}/recommendations` filtered by business segment, marked with `matchReason`.
- **Applied covenants** (Sprint 4): `applied_covenant` table with FK to master, frequency enum (`DAILY`/`WEEKLY`/`MONTHLY`/`QUARTERLY`/`YEARLY`/`CUSTOM`) mapped to Quartz cron, bulk-apply endpoint with duplicate guard, inactive-master guard, custom-cron validation.
- **Scheduling & assessment** (Sprint 5): `monitoring_schedule` table, `MonitoringSchedule` entity, Quartz `AssessmentJob` per applied covenant. `Assessment` entity captures `dataSnapshotJson`, `evaluatedExpression` (variables resolved to literals), `result` (`COMPLIANT`/`BREACHED`/`ERROR`/`PENDING_REVIEW`), `triggeredBy`. Missing-data short-circuits to `ERROR` (not `BREACHED`).
- **Workflow integration & audit** (Sprint 6): `WorkflowClient.startInstance(...)` called on every assessment; `workflowInstanceId` / `workflowError` persisted. `ExpressionChangeLog` records every rule change with `oldExpression`, `newExpression`, `changedBy`, `changedAt`. `PATCH /api/v1/applied-covenants/{id}` for frequency + suspend/resume.
- **Monitoring dashboard** (Sprint 7): `GET /api/v1/monitoring/summary` (per-CP counts), `GET /api/v1/monitoring/cps/{cpId}/covenants` (drill-down), `GET /api/v1/monitoring/applied-covenants/{id}/history` (paginated).
- **Hardening** (Sprint 8): MDC-aware logback config, Actuator metrics + Prometheus scrape endpoint, `PerfSmokeTest` validates search query < 500 ms warm budget over 500 covenants.

### Frontend

- Vite + React 18 + Mantine v8 + TanStack Query/Table shell with theme.
- Typed `fetch` wrapper (`ApiClientError` envelope mapping).
- Covenant Library page: paginated search + filter table.
- Wizard / monitoring screens scaffolded for follow-up sprints.

### Quality gates (validated at every sprint exit)

- **JaCoCo**: ≥ 95% line, ≥ 90% branch coverage on production code (excludes bootstrap, config, DTOs).
- **SpotBugs + FindSecBugs**: `Effort=Max, Threshold=Low`, 0 findings.
- **Vitest v8**: ≥ 95% statements/branches/lines, ≥ 90% functions on the frontend.
- **ESLint + ESLint Security**: 0 warnings.
- **OWASP Dependency-Check** (CI): fail on CVSS ≥ 7.
- **npm audit-ci** (production deps): fail on HIGH / CRITICAL.
- **SonarQube**: project quality gate must pass on every push.

### Known gaps / planned follow-ups

- Frontend wizard (CP onboarding Steps 1–4), monitoring dashboard, and assessment detail page UIs are scaffolded but not yet implemented; backend APIs are complete.
- Quartz switches to JDBC store + Flyway-managed Quartz schema as part of production cutover (memory store used in current build).
- WireMock dev profile that auto-starts on port 9311 (mappings exist in `src/main/resources/wiremock/`; auto-start to be wired with a dev-only `@Bean`).
- Bulk CSV import of covenant master.
- Native breach notifications (currently delegated to Workflow service).
- Multi-tenancy.
