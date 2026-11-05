# Requirements traceability

Source: `docs/integration/05-prd.md` (FR-01 to FR-32, section 7 non-functional requirements).
Review date: 5 November 2026. Stack: `deploy/docker-compose.yml` + `docker-compose.real.yml` on one 8-CPU,
8 GB Docker host, with local Keycloak, SQL Server 2022, WireMock bank stubs and Azure OpenAI.

Status values:

- **Pass**: the acceptance criteria were exercised and met.
- **Partial**: part of the criteria is met and part is missing or unproven.
- **Fail**: the criteria were exercised and not met.
- **Not built**: no implementation.
- **Not verified**: cannot be proven in this environment (needs bank systems or a decision).

Test IDs refer to `feature-test-matrix.md`. "E2E n" is the numbered Playwright test in `ui/e2e/app.spec.ts`.

## Functional requirements

| ID | Requirement | Status | Evidence | Gap |
|---|---|---|---|---|
| FR-01 | Upload agreement / statement / evidence; ≤ 50 MB, ≤ 500 pages; type and asOfDate required; job id and status | Partial | E2E 3, 5, 23; DOC-01..DOC-11 | 600-page PDF accepted (BUG-11); concurrent uploads deadlock on SQL Server (BUG-04). |
| FR-02 | Flow A extraction: metric, operator, threshold, unit, formula, frequency, page, quote, confidence, method | **Fail** | AI-07; golden-set gate 24/24 | "shall not be less than" extracted as `<` (BUG-01); golden labels are extractor drafts (GAP-01). |
| FR-03 | Citation check; failures go to NEEDS_REVIEW | Pass | AI-01: 0 citation failures over 10 agreements | The check does not test the operator, so BUG-01 passes it. |
| FR-04 | Mapping to the library; unmatched become custom candidates | Pass | DPS mapping tests; E2E 4 | |
| FR-05 | Extraction review with page highlight; edit, approve, reject; audited | Pass | E2E 4; UI unit tests | |
| FR-06 | EXTRACTED rows in onboarding; thresholdSource EXTRACTED/USER; ThresholdAudit | Pass | E2E 4; Core onboarding tests | |
| FR-07 | Flow B statements: line items with source cell/page; derived flag; data-quality checks | Pass | E2E 5, 29; DPS statement tests | |
| FR-08 | Values API (C2) per Core contract | Pass | Contract tests; PERF-04: p95 83 ms | |
| FR-09 | Re-assess AUTO covenants within 5 minutes of a REPORTED statement | Pass | PERF-03: 0.2–1.9 s | |
| FR-10 | Missing data gives ERROR with diagnostic, never BREACH | Pass | Core gate tests; RES-01 | |
| FR-11 | Manual covenants with evidence | Pass | E2E 16 | |
| FR-12 | AI suggestions with confidence and citation; human applies; source AI | Pass | Core and DPS tests (17 references) | |
| FR-13 | Waiver / extension tied to latest assessment; extension ≥ today | Pass | E2E 6-8, 21; Core tests | |
| FR-14 | Portfolio isolation, 404/403 outside portfolio, tested per endpoint | Pass | CpAccessGuardTest; DPS test_auth_access; roles.spec | |
| FR-15 | Advisory narrative labelled "AI-generated, not a decision" | Pass | UI label present; status never changed by narrative | |
| FR-16 | End-to-end audit trail from assessment to source page/cell | Pass | E2E 5, 15, 29 | |
| FR-17 | Job operations: failed jobs, step errors, re-run, audited | Pass | E2E 12, 19 | |
| FR-18 | One unified React UI with role menus | Partial | 52 Playwright tests; 122 UI unit tests | Portfolio pages fail above 2,100 CPs (BUG-02) and slow down with portfolio size (BUG-03); CPs onboarded without recommendations are missing (BUG-06). |
| FR-19 | Maker-checker, default ON, switchable, audited | Pass | E2E 6-8 | |
| FR-20 | APPROVER enters missing value with note; manual badge; re-assess | Pass | DPS and Core tests (FR-20 references) | |
| FR-21 | One agreement linked to several CPs | Pass | DPS tests | |
| FR-22 | Certificates per CP and period with states and OVERDUE | Pass | E2E 25, 30, 31; Core tests | |
| FR-23 | Currency and units; dated FX table | Pass | Core and DPS tests (11 references) | |
| FR-24 | Period comparison and Excel exports | Pass | E2E 9, 11; Core tests | |
| FR-25 | Industry risk treemap, exposure by sector, entity mix | Partial | E2E 22 | No treemap and no exposure figure; only breach rate by segment and sector. |
| FR-26 | Trend indicator per covenant | Pass | E2E 15 | |
| FR-27 | Ask AI scoped to the portfolio, cites records, refuses outside scope | Pass | E2E 10; AI-06 | Agreement Q&A (E2E 26) returns planted or irrelevant text (BUG-12). |
| FR-28 | Financial news (optional, R3) | Not built | | Optional and off by default in the PRD. |
| FR-29 | Agreement terms beyond thresholds (display) | Pass | DPS tests; UI extraction review | Machine-usable form is R3. |
| FR-30 | Facility dates from agreement or CP service, never invented | Pass | DPS and Core tests | |
| FR-31 | Escalation via the notification service | Partial | Reports and task queue show escalations | No escalation notification is sent. |
| FR-32 | Management reports, Excel and PDF, role-scoped | Partial | E2E 9, 28 | Open-breaches report 500 bank-wide above 2,100 CPs (BUG-02); 4–6 s for 60 CPs (BUG-03). |

## Non-functional requirements

| Area | Requirement | Status | Evidence | Gap |
|---|---|---|---|---|
| Security | SSO; JWT validated in gateway and both services; six roles; no default secrets; CSP; ASVS L2 target; pen test | Partial | SEC-01..SEC-08 | Keycloak stands in for Entra ID; no penetration test; no Key Vault in this environment. |
| Privacy | Azure OpenAI private endpoint; minimal text; logs ≤ 90 days | Not verified | | Private endpoint and retention are environment and policy decisions. |
| Reliability | Core works with DPS down; DPS retries; idempotent publish/report | Partial | RES-01..RES-04 | Core stays up, but assessments use the staged copy instead of `DATA_SOURCE_FAILURE` (GAP-03). |
| Performance | Flow A 50 pages p95 ≤ 90 s; Flow B p95 ≤ 60 s; C2 p95 ≤ 500 ms; daily run 10k covenants ≤ 30 min | Pass | PERF-01..PERF-05 | All four targets met with wide margins (single user). |
| Scalability | DPS workers scale on queue depth; Core 2+ instances with clustered Quartz | **Fail** | OPS-03; LOAD-08, LOAD-09 | Two-node Quartz works, but the app overloads at about 25 concurrent RMs with 60-CP portfolios (BUG-03) and fails above 2,100 CPs (BUG-02). |
| Data | SQL Server per-service schema; migrations; daily backup and restore | Pass | Flyway V1..V30, Alembic; restore drill identical | |
| Quality gates | Core 95%/90% + SpotBugs + OWASP; DPS ≥ 90%, golden set, bandit/semgrep, pip-audit; contract tests | Partial | Core 96%/90%, DPS 98%, pip-audit, Trivy | No bandit or semgrep step in CI; golden set has 10 agreements, PRD asks ≥ 20 agreements and ≥ 20 statements, all labelled by analysts. |
| Observability | Correlation id end to end; per-job step metrics; LLM tokens and cost per job; dashboards and alerts | Partial | X-Correlation-Id in nginx and Core MDC; job steps stored | No token or cost tracking per job; dashboards and alerts are not part of the repository. |
| Accessibility | WCAG 2.1 AA | Pass (automated) | axe light and dark, 4 roles, 0 violations; screen-reader structure test | No manual screen-reader session with a real user. |
