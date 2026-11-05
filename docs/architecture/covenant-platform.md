# covenant-platform — Covenexa (the product built on both apps)

| | |
|---|---|
| Built by | Our team, on top of [covenanttrackingphase1.2](covenanttrackingphase1.2.md) and [covenant-monitoring](covenant-monitoring.md) |
| Location | `C:\Users\vishw\covenant-platform`, GitHub `Vishwas2311/loan-covenant-platform` |
| Folders | `core/` (Java service), `dps/` (Python service), `ui/` (React app), `deploy/` (Docker Compose files, Keycloak realm, stack-init, mail bridge, smoke test), `contracts/` (OpenAPI contracts, Redocly lint), `golden-set/` (extraction test set, review tool), `loadtest/`, `scripts/` (line-ending fixer, demo-data reset), `.github/workflows/` (CI, Security, E2E), `docs/`, `outputs/` (readiness review), `graphify-out/` (code knowledge graph) |
| In one line | covenant-monitoring became **Core**, the system of record that decides compliance; covenanttrackingphase1.2 became the **Document Processing Service (DPS)**, which reads agreements and statements; a new UI, real sign-in, borrower portal and production packaging were built around them |

Back to the [overview](README.md). What each page does for a user: [user-guide.md](../user-guide.md).

## 0. The whole system in one diagram

Every part and how it connects, top to bottom: users, the gateway and sign-in, the UI pages, Core and DPS with the contracts between them, the data, each bank service, the AI provider, and how it is built and run. Sections 2–9 below zoom into each part.

```mermaid
%%{init: {"flowchart": {"wrappingWidth": 320, "nodeSpacing": 20, "rankSpacing": 50}}}%%
flowchart TB
    subgraph L1["1 · USERS"]
        direction LR
        staff(["Bank staff<br/>RM · Approver · Author<br/>Admin · Auditor · Support"])
        borrower(["Borrower"])
    end

    subgraph L2["2 · EDGE AND SIGN‑IN"]
        direction LR
        gw["nginx gateway :8080<br/>serves the app · /api → Core<br/>/dps → DPS · correlation id"]
        idp{{"Keycloak / Entra ID<br/>OIDC + PKCE · JWT + roles"}}
    end

    subgraph L3["3 · UI — React 18 · Mantine · TanStack Query"]
        direction TB
        subgraph UP["Pages"]
            direction LR
            pPort["Portfolio"]
            pTask["My tasks"]
            pRep["Reports<br/>+ Ask AI"]
            pCp["CP workspace"]
            pOnb["Onboarding"]
            pDoc["Documents"]
            pLib["Library"]
            pMea["Measures"]
            pDs["Data sources"]
            pSet["Settings"]
            pJob["Doc jobs"]
            pSup["Support"]
            pPortal["Borrower<br/>portal"]
        end
        uiapi["API layer — typed calls with the user's token"]
        UP --> uiapi
    end

    subgraph L4["4 · CORE — Spring Boot 3.5 · Java 17 · :9310 — from covenant‑monitoring"]
        direction TB
        csec["Request pipeline<br/>JWT + roles · CP isolation · support token"]
        cjob["Quartz scheduler (clustered)<br/>5 min task sync · 02:00 checks<br/>05:30 fetch-ahead · 06:00 daily run"]
        subgraph CA["Business areas — 34 controllers"]
            direction LR
            c1["Library +<br/>formula engine"]
            c2["Onboarding"]
            c3["Assessment<br/>engine"]
            c4["Monitoring<br/>+ review"]
            c5["Borrower side"]
            c6["Reports<br/>+ e-mail"]
            c7["Support<br/>toolkit"]
        end
        cclients["Bank-service clients<br/>9 WebClients · OAuth · retries + circuit breakers"]
        csec --> CA
        cjob -->|"triggers checks, sync, e-mails"| CA
        CA --> cclients
    end

    subgraph L5["5 · DPS — FastAPI · CrewAI Flows · Python 3.12 · :9400 — from covenanttrackingphase1.2"]
        direction TB
        dguard["Request checks<br/>JWT · CP access via Core · rate limits"]
        subgraph DE["Endpoints"]
            direction LR
            dUp["Uploads +<br/>documents"]
            dRev["Term review<br/>+ spreads"]
            dAsk["Ask AI +<br/>agreement Q&A"]
            dJob["Jobs<br/>retry · resume"]
            dServe["Serving Core<br/>C1 · C2 · C3"]
        end
        subgraph DW["Workers — job queue, standard + heavy pools"]
            direction LR
            fa["Flow A<br/>agreements"]
            fb["Flow B<br/>statements"]
            parse["ParserEngine<br/>pypdf → OCR → Docling"]
            dout["Event outbox<br/>DATA_EVENT"]
            fa --> parse
            fb --> parse
            fb --> dout
        end
        dguard --> DE
        DE -->|"job queue"| DW
    end

    subgraph L6["6 · DATA"]
        direction LR
        sqlcore[("SQL Server — Core<br/>36 cov_* tables · QRTZ_*")]
        sqldps[("SQL Server — DPS<br/>documents · jobs · terms · values")]
        store[("Document store<br/>original files")]
    end

    subgraph L7["7 · BANK SERVICES — WireMock stand‑ins locally"]
        direction LR
        bCp{{"Credit<br/>proposals"}}
        bAtt{{"Covenant<br/>attributes"}}
        bData{{"Data API<br/>+ catalogue"}}
        bTab{{"Bank data<br/>tables"}}
        bWf{{"Workflow<br/>cases"}}
        bFile{{"File<br/>service"}}
        bNot{{"Notification<br/>(e-mail)"}}
        bMl{{"ML thresholds<br/>(mock in Core locally)"}}
        bAi{{"AI status<br/>suggestions"}}
    end

    subgraph L8["8 · AI PROVIDER"]
        aoai{{"Azure OpenAI"}}
    end

    subgraph L9["9 · BUILD, RUN AND OPERATE"]
        direction LR
        ci["GitHub Actions<br/>CI · Security · nightly E2E"]
        deploy["Docker + Compose"]
        obs["Operations<br/>actuator · runbook"]
    end

    staff -->|HTTPS| gw
    borrower -->|HTTPS| gw
    gw <-->|"sign-in"| idp
    gw -->|"serves the app"| L3
    uiapi -->|"/api + JWT"| csec
    uiapi -->|"/dps + JWT"| dguard

    csec <-->|"Core → DPS: C1 proposals · C2 figures · C3 AI (opt-in)<br/>DPS → Core: access check · C4 library + measures · C5 DATA_EVENT → re-check"| dguard

    L4 -->|JDBC| sqlcore
    L5 -->|SQL| sqldps
    L5 -->|files| store

    cclients -->|"CP · RM/WRM · loan status"| bCp
    cclients -->|"eligibility"| bAtt
    cclients -->|"REST values + catalogue"| bData
    cclients -->|"JDBC values"| bTab
    cclients -->|"case per CP"| bWf
    cclients -->|"evidence · certificates"| bFile
    cclients -->|"summaries · reminders · reports"| bNot
    cclients -->|"suggested thresholds"| bMl
    cclients -->|"C3 default"| bAi

    DE -->|"Ask AI · Q&A · C3"| aoai

    pPort ~~~ pTask ~~~ pRep ~~~ pCp ~~~ pOnb ~~~ pDoc ~~~ pLib ~~~ pMea ~~~ pDs ~~~ pSet ~~~ pJob ~~~ pSup ~~~ pPortal
    c1 ~~~ c2 ~~~ c3 ~~~ c4 ~~~ c5 ~~~ c6 ~~~ c7
    dUp ~~~ dRev ~~~ dAsk ~~~ dJob ~~~ dServe
    aoai ~~~ L9
```

## 1. Technology stack, and where each piece came from

| Layer | Technology | Came from |
|---|---|---|
| UI | React 18, Mantine, TanStack Query, Vite build; served by **nginx**, which is also the gateway on port 8080 (`/api` → Core, `/dps` → DPS) | New |
| Sign-in | OpenID Connect: **Keycloak 26** locally, **Microsoft Entra ID** in production; JWT on every call; roles RM, APPROVER, AUTHOR, ADMIN, AUDITOR, SUPPORT, BORROWER | New |
| Core | Java 17, Spring Boot 3.5, Spring Security (JWT resource server), JPA, Quartz (clustered in production), formula engine, Flyway `V1`–`V32` | covenant-monitoring, extended |
| DPS | Python 3.12, FastAPI, **CrewAI Flows** (Flow A agreements, Flow B statements), SQLAlchemy + Alembic, pypdf, RapidOCR, **Docling** (heavy pool), openpyxl, xlrd | covenanttrackingphase1.2, rebuilt |
| AI | **Azure OpenAI** through DPS: Ask AI, agreement questions, statement narratives; token use stored per call; per-user rate limits on calls that cost money. Core's AI status suggestions (C3) call an external service by default (WireMock locally) and use DPS only when `COVENANT_AI_PROVIDER=dps` | Both, consolidated |
| ML thresholds | Core asks an ML service for suggested thresholds at onboarding; locally a mock inside Core answers (`/internal/mock-ml`) | covenant-monitoring |
| Database | **SQL Server 2022** (Core's `covenant` database and DPS's tables), `READ_COMMITTED_SNAPSHOT` on | covenant-monitoring |
| Files | Document store volume for original PDFs and spreadsheets | New |
| Bank services | Credit proposals (CP details, RM / WRM, loan status — also used by the nightly loan sync), covenant attributes, data API + data catalogue (REST data sources), customer SQL Server tables (MSSQL data sources), notification, workflow-case and file services; **WireMock** stand-ins locally, with OAuth client-credentials or a fixed token per service | covenant-monitoring |
| API contracts | OpenAPI files `contracts/dps-api.yaml` and `contracts/core-additions.yaml`, linted with Redocly | New |
| Packaging | Docker images for Core, DPS (standard and heavy) and UI; Docker Compose; optional Mailpit and two-node files | New |
| CI | GitHub Actions: build and tests, coverage gates, SpotBugs, golden-set gate, DPS on SQL Server, image builds; Security workflow (bandit, semgrep, pip-audit, npm audit, Trivy); scheduled E2E workflow (Playwright on the full stack) | New |

## 2. Deployment (local Docker stack)

```mermaid
%%{init: {"flowchart": {"wrappingWidth": 420}}}%%
flowchart TB
    browser(["Browser"])

    subgraph host["One machine — docker compose (deploy/)"]
        direction TB
        ui["ui — nginx :8080<br/>React app + gateway<br/>/api → core · /dps → dps-api"]
        kc["keycloak :8180<br/>realm 'covenant', test users<br/>(docker-compose.real.yml)"]
        core["core — Spring Boot :9310<br/>actuator :9320 (internal)<br/>mock ML endpoint /internal/mock-ml (local only)"]
        dpsapi["dps-api — FastAPI :9400"]
        w1["dps-worker<br/>standard pool"]
        w2["dps-worker-heavy<br/>heavy pool (Docling)"]
        sql[("sqlserver :1433 — SQL Server 2022<br/>covenant DB · DPS tables<br/>demo customer tables (MSSQL data sources)")]
        init["stack-init<br/>creates databases and logins"]
        stubs["bank-stubs — WireMock :9311<br/>CP + RM/WRM · covenant attributes · data catalogue + values<br/>obligor values · notifications · workflow cases<br/>file upload / download · AI suggestions"]
        vol[("document store volume")]
        mp["mail-bridge :8090 → mailpit :8025<br/>(docker-compose.mail.yml, optional)"]
    end

    aoai{{"Azure OpenAI<br/>(internet)"}}

    browser --> ui
    browser <--> kc
    ui --> core
    ui --> dpsapi
    core --> sql
    dpsapi --> sql
    w1 --> sql
    w2 --> sql
    dpsapi --> vol
    w1 --> vol
    w2 --> vol
    init --> sql
    core --> stubs
    core -->|reads customer tables| sql
    core -. e-mails .-> mp
    core <--> dpsapi
    dpsapi --> aoai
    w1 --> aoai
    core -. verifies JWT .-> kc
    dpsapi -. verifies JWT .-> kc
```

In production the same images run on the bank's platform, Keycloak is replaced by Entra ID, WireMock by
the bank's real services, and Core can run as several nodes (`docker-compose.scale.yml` shows two).

## 3. Component architecture

```mermaid
%%{init: {"flowchart": {"wrappingWidth": 420}}}%%
flowchart TB
    staff(["RM · Approver · Author · Admin · Auditor · Support"])
    borrower(["Borrower"])
    idp{{"Keycloak / Entra ID<br/>OIDC, JWT, roles"}}

    subgraph uiL["UI — React + Mantine (new)"]
        spa["Portfolio · My tasks · CP workspace · Onboarding · Documents<br/>Reports + Ask AI · Covenant library · Measures · Data sources · Settings<br/>Document jobs · Support tools"]
        portal["Borrower portal"]
    end

    subgraph coreL["Core — Spring Boot (from covenant‑monitoring)"]
        sec["Security: JWT resource server, role checks,<br/>CP isolation (CpAccessGuard), support token"]
        kept["Kept from covenant-monitoring:<br/>library · measures · data sources · formula engine<br/>onboarding · assessment engine · staging + freshness gate<br/>waiver / extension · evidence · support toolkit"]
        added["Added in covenant-platform:<br/>maker-checker approvals · tasks + alerts · reports + exports<br/>certificates + reminders · borrower portal · letters<br/>loan-system sync · escalation digest · FX · stale-figures fallback<br/>portfolio cache · DPS clients (C1 recommendations, C2 values, C3 AI)"]
        q["Quartz (clustered)<br/>covenant checks · 05:30 fetch-ahead · 06:00 daily run · 5-min task sync"]
    end

    subgraph dpsL["DPS — FastAPI (from covenanttrackingphase1.2)"]
        dapi["API: uploads, documents, term review,<br/>values (C2), recommendations (C1), Ask AI, AI usage"]
        acc["Access check: asks Core whether this user may see this CP<br/>(answers cached 30 s; 503 when Core is busy)"]
        fa["Flow A — agreements<br/>parse → extract → normalise → validate<br/>→ publish / needs review / failed / heavy"]
        parse["ParserEngine<br/>pypdf text → RapidOCR → Docling (heavy)"]
        ai2["advisor · ask · agreement_qa<br/>advisory only, rate-limited"]
        fp["Flow state checkpoints<br/>dps_flow_state"]
        fb["Flow B — statements<br/>parse → extract and calculate → validate<br/>→ report / needs review / failed / heavy"]
        wk["Workers: standard + heavy pools,<br/>job queue with leases and 3 attempts"]
        ob["Event outbox → Core<br/>DATA_EVENT after new figures"]
    end

    sql[("SQL Server")]
    store[("Document store")]
    aoai{{"Azure OpenAI"}}
    bank{{"Bank services"}}

    staff --> spa
    borrower --> portal
    spa & portal <--> idp
    spa & portal -->|/api with JWT| sec
    spa -->|/dps with JWT| dapi
    sec --> kept & added
    q --> kept & added
    kept & added --> sql
    added -->|"C1 recommendations · C2 values · C3 AI (opt-in)"| dapi
    dapi --> acc -->|is this CP yours?| sec
    dapi --> wk --> fa & fb
    fa & fb --> parse
    fa & fb -.-> fp
    dapi --> ai2 --> aoai
    fa & fb --> store
    fb --> ob -->|DATA_EVENT| sec
    dapi & fa & fb --> sql
    added --> bank
```

## 3b. Inside Core — every controller and the services behind it

| Area | Controllers (`/api/v1/...`) | Main services | Kept / new |
|---|---|---|---|
| Covenant library | `CovenantMasterController` (`/covenants`), `FormulaController` (`/formulas`), `CronValidationController` (`/schedules/validate-cron`) | `CovenantMasterService`, `CovenantConditionValidator`, `RuleRecheckService` (re-check every CP when a rule changes), `FormulaEngine` | Kept; re-check new |
| Measures and data sources | `MeasureController`, `DataSourceController`, `DataSourceTableController` | `MeasureService`, `MeasureCatalogService`, `MeasureChangeListener`, `DataSourceService`, `DataSourceTableService`, `ColumnDiscoveryService`, `JdbcDataSourceRegistry` | Kept |
| Onboarding | `RecommendationController` (`/credit-proposals/{cpId}/...`), `AppliedCovenantController`, `MlThresholdController`, `MockMlThresholdController` (`/internal/mock-ml`, local) | `RecommendationService`, `OnboardingRecommendationService`, `ExtractionRecommendationService` + `WebClientExtractionRecommender` (C1), `MlThresholdService`, `AppliedCovenantService`, `ObligorMappingService` | Kept; C1 new |
| Checking | `AssessmentController` (`/assessments/run`), `DataEventController` (`/data-events`, C5) | `AssessmentService`, `FormulaMeasureBinder`, `MeasureAggregationResolver`, `IngestionOrchestrationService`, `StagingIngestionService`, `IngestionPreStageService`, `DataEventService`, `ScheduleService` | Kept; data events, stale fallback new |
| Monitoring and review | `MonitoringController`, `CovenantAssessmentController` (review grid, status, waiver decision, attachments), `CpCycleController`, `AlertController`, `AnalyticsController` | `MonitoringService`, `ReviewService`, `CpDirectoryService`, `PortfolioCache`, `CpChangeListener`, `AlertService`, `AnalyticsService`, `TrendService`, `CpLineageService`, `CpCycleService`, `AiSuggestionService` | Kept; cache, alerts, analytics new |
| Tasks | `WorkQueueController` (`/tasks`) | `WorkQueueService`, `TaskSyncJob`, `TaskSyncTracker` | New |
| Certificates, borrowers, letters | `CertificateRequirementController`, `BorrowerReminderController`, `PortalAccessController`, `PortalController` (`/portal`), `LetterController` | `CertificateRequirementService`, `ReminderService`, `PortalService`, `LetterService` (+ `DocxWriter`), `AttachmentService` | New (attachments kept) |
| Reports and exports | `ReportController` (`/reports/open-breaches` + `.pdf` / `.xlsx`), `SavedReportController`, `ExportController` (`comparison.xlsx`, `covenants/export.xlsx`) | `BreachReportService` (+ `TextPdfWriter`), `ScheduledReportService`, `ComparisonService` (+ `XlsxWriter`) | New |
| Notifications and runs | `NotificationRunController` (`/monitoring/notification-runs`, manual run + status) | `BreachNotificationService`, `ManualRunService` (`cov_manual_run`), `EscalationService`, `DailyBreachRunJob`, `DailyRunInitializer`, `LoanSyncService` | Kept; manual run, escalation, loan sync new |
| Settings | `ReportingSettingsController`, `FxController` (`/fx/rates`) | `ReportingSettingsService`, `FxService` | Kept; FX new |
| Support (support token) | `OpsDiagnosticsController`, `OpsRemediationController`, `OpsHealthController`, `OpsAuditController` (audit, sent e-mails), `OpsCleanupController` | `OpsDiagnosticsService`, `OpsRemediationService`, `OpsHealthService`, `OpsAuditService`, `CpDataCleanupService`, `SupportEntityKeyResolver` | Kept |

Cross-cutting pieces:

| Piece | Classes | What it does |
|---|---|---|
| Sign-in | `SecurityConfig`, `JwtAuthConverter`, `Roles`, `SecurityProperties` | Checks the JWT (issuer, audience, signature) and maps its roles |
| Dev mode | `DevHeaderAuthenticationFilter`, `CurrentUserContext` | "Act as" header sign-in for development only; refused with the prod profile |
| CP isolation | `CpAccessGuard` | Every CP-scoped call checks that the user may see that CP |
| Support gate | `SupportTokenInterceptor`, `SupportTokenStartupCheck`, `SupportProperties` | `/api/v1/ops/**` needs the support token; Core will not start without one |
| Internal port | `ManagementPort` | Actuator (health, metrics, Prometheus) only on port 9320 |
| Request logging | `RequestLogContextFilter` | Adds the request id and user to every log line |
| Bank-service clients | 9 `WebClient*` clients, `WebClientsConfig`, `ClientsProperties`, `ClientCredentialsTokenProvider`, resilience4j | Credit proposals, attributes, data API, data catalogue, workflow, notifications, files, ML, AI suggestions — with retries and circuit breakers |
| Retries | `TransientRetry` | Re-runs a whole transaction that SQL Server chose as a deadlock victim |
| Health | `DataSourceHealthIndicator` | Reports each data source in `/actuator/health` |
| Dev stubs | `WireMockDevConfig` | Starts WireMock inside Core for the dev profile |

## 3c. Inside DPS — every module

| Module | What it does |
|---|---|
| `main.py`, `settings.py`, `db.py` | App factory, configuration, database sessions; refuses SQL Server without `READ_COMMITTED_SNAPSHOT` |
| `api.py`, `errors.py` | All `/dps/v1` endpoints and the error envelope |
| `auth.py`, `access.py` | Checks the user's JWT; asks Core whether the user may see each CP (cached 30 s) |
| `service_auth.py`, `core_client.py` | DPS's own client-credentials token, and its calls to Core (library, measures, data events) |
| `ratelimit.py` | Per-user limits on Ask AI, agreement questions and uploads |
| `jobs.py`, `worker.py` | Job queue on `dps_job` (leases, 3 attempts, heavy pool); the worker process and the event outbox sender |
| `flows/intake.py` | Flow A — loan agreements |
| `flows/statement.py` | Flow B — financial statements |
| `flows/persistence.py` | Saves flow checkpoints in `dps_flow_state`, so a review can resume the flow |
| `parsing.py` | ParserEngine: pypdf text first, RapidOCR for pages without text, Docling in the heavy pool |
| `extraction/agreement.py`, `extraction/mapping.py`, `extraction/validation.py` | Find covenants; map them to Core's library; citation, confidence and operator checks |
| `extraction/terms_info.py` | Agreement facts that are not covenants (testing basis, cure periods, definitions) |
| `extraction/statement.py`, `catalog.py` | Read statement line items (PDF, XLSX, XLS, CSV) and the line-item catalogue served to Core |
| `services.py` | Recommendations (C1, amendments replace earlier terms), values (C2), removal, spreads |
| `advisor.py`, `ask.py`, `agreement_qa.py` | AI suggestions (C3), Ask AI over the user's own portfolio, questions about one agreement — all advisory |
| `usage.py` | Tokens and cost of every model call (`dps_ai_usage`) |
| `storage.py` | Content-addressed document store (to be replaced by the bank's file service) |
| `models.py` | The `dps_*` tables (Alembic migrations in `dps/migrations`) |

## 3d. Inside the UI

| Part | Files | What it does |
|---|---|---|
| Shell and menu | `App.tsx`, `auth/access.ts` | Menu by role, "not for your role" page, route guards |
| Sign-in | `auth/oidc.ts`, `auth/identity.ts`, `auth/useIdentity.ts` | OIDC with PKCE in real mode; "Act as" picker in dev mode; borrower-only users get the portal |
| API layer | `api/client.ts`, `api/endpoints*.ts`, `api/types*.ts` | Typed calls to `/api` and `/dps` with the token |
| Pages | `Portfolio`, `MyTasks`, `CpWorkspace` (+ `CovenantPanel`, `CpExtras`, `CpMore`), `Onboarding` (+ `CpQueues`), `Documents`, `Library`, `Reports` (+ `Charts`, `Phase3`), `Measures`, `DataSources`, `Settings`, `Ops` (document jobs, Ask AI), `Support`, `Portal`, `Phase4` | One page per menu item |
| Shared components | `AlertBell`, `CpPicker`, `Board` (KPIs, chart cards), `charts.tsx`, `Status`, `ColorSchemeToggle`, `BrandMark` | Alerts, CP search, charts, light / dark |

## 3e. Contracts between Core and DPS

| Contract | Direction | Endpoint | Purpose |
|---|---|---|---|
| C1 | Core → DPS | `POST /dps/v1/recommendations` | Published agreement covenants for onboarding |
| C2 | Core → DPS | `POST /dps/v1/values`, `GET /dps/v1/catalog` | Statement figures, through Core's REST data-source contract |
| C3 | Core → DPS (opt-in) | `POST /dps/v1/ai-suggestions` | Advisory AI status suggestions, when `COVENANT_AI_PROVIDER=dps` |
| C4 | DPS → Core | Core library and measures APIs | Map extracted terms to library covenants and measures |
| C5 | DPS → Core | `POST /api/v1/data-events` | "New figures for this CP and period" — Core re-checks the covenants that use them |
| C6 | Core → bank | file service | Evidence and certificate files (DPS keeps its own document store for now) |
| Access check | DPS → Core | Core's CP access check | One rule for who may see a CP, applied by both services |

## 4. Journey A — loan agreement to monitored covenants

```mermaid
sequenceDiagram
    autonumber
    actor RM
    participant UI
    participant DPS as DPS API
    participant W as DPS worker (Flow A)
    participant Core
    participant AI as Azure OpenAI

    RM->>UI: Documents → upload agreement (type Loan agreement, linked CPs)
    UI->>DPS: POST /dps/v1/documents (JWT)
    DPS->>Core: may this user write to these CPs?
    Core-->>DPS: yes
    DPS-->>UI: 202 documentId, jobId
    W->>W: parse (pypdf, scanned → heavy pool, Docling OCR)
    W->>W: extract covenants: metric, operator, threshold, page, quote
    W->>Core: covenant library (map each term to a library covenant)
    W->>W: validate: citation check, confidence, operator direction
    alt every term confident
        W->>W: PUBLISHED
    else needs a person
        W->>W: NEEDS_REVIEW
        RM->>UI: approve / edit / reject each term, Review done
    end
    RM->>UI: Onboarding → CP
    UI->>Core: GET onboarding recommendations
    Core->>DPS: C1 recommendations for the CP (amendments replace earlier terms)
    Core-->>UI: library matches + agreement terms + ML suggestions
    RM->>UI: tick covenants, Apply
    UI->>Core: POST applied covenants
    Core->>Core: create Quartz schedule per covenant
    opt questions
        RM->>UI: Ask about this agreement
        UI->>DPS: POST /dps/v1/documents/{id}/ask
        DPS->>AI: question + relevant clauses
    end
```

## 5. Journey B — statement figures to a covenant result

```mermaid
sequenceDiagram
    autonumber
    actor RM as RM or borrower
    participant UI
    participant DPS as DPS API
    participant W as DPS worker (Flow B)
    participant Core
    participant DB as SQL Server

    RM->>UI: upload statement (PDF, XLSX, XLS, CSV) + period end
    UI->>DPS: POST /dps/v1/documents
    W->>W: parse, read line items with page or cell reference
    W->>W: derive inputs (EBITDA, total debt...), data-quality checks
    alt checks pass
        W->>DB: dps_statement_value (REPORTED)
        W->>DB: dps_event_outbox DATA_EVENT
    else needs a person
        RM->>UI: corrections, Save
    end
    W->>Core: deliver DATA_EVENT (retries)
    Core->>Core: find covenants that use these figures
    Core->>DPS: C2 values for the CP and period (freshness gate)
    alt DPS answers
        Core->>DB: refresh cov_staging_value
    else DPS down, stored copy exists
        Core->>Core: use stored copy, note "Data may be stale"
    end
    Core->>Core: formula engine → COMPLIANT / BREACHED / ERROR
    Core->>DB: append cov_assessment, tasks and alerts follow
```

## 6. Sign-in and authorisation

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant UI as nginx + React
    participant IdP as Keycloak / Entra ID
    participant Core
    participant DPS

    U->>UI: open http://localhost:8080
    UI->>IdP: redirect to sign-in (OIDC, PKCE)
    U->>IdP: user name + password
    IdP-->>UI: access token (JWT: user, roles)
    UI->>Core: /api/... Bearer JWT
    Core->>Core: check signature, issuer, audience, role, CP owner check
    UI->>DPS: /dps/... Bearer JWT
    DPS->>DPS: check signature, issuer, audience
    DPS->>Core: access check for the CP (cached 30 s)
    Note over Core,DPS: Service to service: client-credentials tokens (SERVICE role)
```

## 7. What runs by itself

```mermaid
gantt
    title Daily timetable (India Standard Time)
    dateFormat HH:mm
    axisFormat %H:%M
    section Every 5 minutes
    Task sync (tasks and alerts for every CP) :t1, 00:00, 24h
    section Per covenant
    Own scheduled check, spread over the hour :c1, 02:00, 60m
    section Portfolio
    Fetch-ahead for covenants due in 3 days :p1, 05:30, 20m
    Daily run — loan sync, checks, RM summaries, reminders, reports, escalation :p2, 06:00, 30m
```

Also continuous: DPS workers read every upload; a new or corrected statement triggers re-checks within
seconds; the DPS outbox retries delivery to Core.

## 8. Data model added on top of covenant-monitoring

```mermaid
erDiagram
    dps_document ||--o{ dps_document_cp_link : "linked CPs"
    dps_document ||--o{ dps_job : "processing jobs"
    dps_document ||--o{ dps_extracted_term : "covenants read"
    dps_document ||--o{ dps_statement_value : "figures read"
    dps_document |o--o| dps_document : "amends"

    dps_document {
        string id PK
        string type "LOAN_AGREEMENT / FINANCIAL_STATEMENT / EVIDENCE"
        string status
        string amends_document_id FK
        string sha256
    }
    dps_extracted_term {
        string id PK
        string document_id FK
        string covenant_master_id
        string expression
        float confidence
        string status "PROPOSED / NEEDS_REVIEW / PUBLISHED / REJECTED / WITHDRAWN"
        int page
        string quote
    }
    dps_statement_value {
        string id PK
        string document_id FK
        string cp_id
        string key
        date as_of_date
        float value
        bool superseded
    }
```

New Core tables, added to covenant-monitoring's 23:

```mermaid
erDiagram
    cov_applied_covenant ||--o{ cov_work_task : "tasks"
    cov_applied_covenant ||--o{ cov_letter_log : "letters"
    cov_credit_proposal_cache ||--o{ cov_certificate_requirement : "certificates due"
    cov_credit_proposal_cache ||--o| cov_borrower_contact : "reminders to"
    cov_borrower_contact ||--o{ cov_reminder_log : "sent"
    cov_credit_proposal_cache ||--o{ cov_portal_access : "borrower users"
    cov_portal_access ||--o{ cov_portal_submission : "certificates, statements"
    cov_loan_sync_run ||--o{ cov_loan_sync_change : "changes"
```

New Core tables: `cov_work_task`, `cov_saved_report`, `cov_certificate_requirement`,
`cov_borrower_contact`, `cov_reminder_log`, `cov_portal_access`, `cov_portal_submission`,
`cov_letter_log`, `cov_loan_sync_run`, `cov_loan_sync_change`, `cov_data_event`, `cov_fx_rate`,
`cov_manual_run` (36 `cov_*` tables in all, up from 23). DPS tables: `dps_document`,
`dps_document_cp_link`, `dps_job`, `dps_extracted_term`, `dps_statement_value`, `dps_event_outbox`,
`dps_audit_event`, `dps_ai_usage`, `dps_flow_state`.

## 9. Build and quality pipeline

```mermaid
%%{init: {"flowchart": {"wrappingWidth": 420}}}%%
flowchart LR
    push["git push"] --> ci & secw
    nightly["schedule / manual"] --> e2e
    subgraph ci["CI workflow"]
        b1["Core: Maven verify<br/>unit + integration, JaCoCo, SpotBugs"]
        b2["DPS: ruff, pytest 90%+ coverage"]
        b3["Golden set: tests + synthetic gate<br/>recall, precision, thresholds"]
        b5["DPS on SQL Server: migrations,<br/>no drift, tests"]
        b4["UI: Vitest coverage, type-check, build"]
        b6["Docker images: compose check, builds"]
    end
    subgraph secw["Security workflow"]
        s1["semgrep"]
        s2["bandit (DPS)"]
        s3["pip-audit (DPS)"]
        s4["npm audit (UI)"]
        s5["Trivy: Core, DPS, UI images"]
    end
    subgraph e2e["E2E workflow"]
        e1["Full stack in Docker<br/>throwaway secrets, Keycloak"]
        e2["Playwright: 58 tests<br/>roles, journeys, accessibility"]
        e1 --> e2
    end
```

## 10. What came from where

| Capability | From covenanttrackingphase1.2 | From covenant-monitoring | New in covenant-platform |
|---|---|---|---|
| Reading loan agreements | Regex extraction, OCR | — | Flow A, citation check, review screen, amendments, security covenants |
| Reading statements | Excel/PDF reading, ratio formulas, metric aliases | — | Flow B, cell references, data-quality checks, `.xls`, corrections, Values API (C2) |
| Covenant rules | Master covenant list, custom formulas | Library, eligibility, formula language, measures, data sources | Agreement terms feed onboarding |
| Checking covenants | One run per upload | Quartz, assessment engine, staging | Spread schedules, clustered scheduler, re-check on new data, stale-figures fallback |
| Breach workflow | Human review of breaches | Waiver / extension, evidence, AI suggestions | Maker-checker, tasks, alerts, letters, escalation digest |
| AI | 7 CrewAI agents on gpt-4.1 | AI status-suggestion client | Azure OpenAI, Ask AI limited to the user's CPs, agreement Q&A, token tracking |
| Users and security | None | `X-User` header | OIDC sign-in, 7 roles, CP isolation in Core and DPS, support token |
| Borrowers | — | — | Borrower portal, reminders, certificate tracking |
| Storage | JSON files | H2 / SQL Server | SQL Server for both services, document store |
| Operations | Run from a terminal | Support toolkit | Docker images, runbook, CI security gates, load-tested on one and two Core nodes |
