# covenant-monitoring — "Covenant Monitoring System" (the second team's app)

| | |
|---|---|
| Built by | The second team |
| Location | The folder `covenant-monitoring`, kept outside this repository, on the development laptop where the integration was done (a plain folder, not a git repository; copy it separately if you need it) |
| Read from | `README.md`, `CLAUDE.md`, `backend/pom.xml`, `backend/src/main/resources/application*.yml`, `db/migration`, `wiremock/mappings`, the Java packages under `com.crisil.covenant`, `frontend/package.json`, `frontend/vite.config.ts`, `frontend/src/pages` |
| In one line | A bank-grade Java service that keeps a covenant library, applies covenants to credit proposals (CPs), checks them on a schedule against the bank's own data, and runs the breach workflow |

Back to the [overview](README.md).

## 1. Technology stack

| Layer | Technology | Where |
|---|---|---|
| Backend language | Java 17 | `backend/pom.xml` |
| Framework | Spring Boot 3.5.14: Web (MVC), Data JPA (Hibernate, `ddl-auto: validate`), Validation, Actuator, WebFlux (for WebClient only) | `backend/pom.xml` |
| Scheduling | Quartz, JDBC job store (`QRTZ_*` tables), not clustered (`isClustered: false`) | `application.yml` |
| Database | H2 file database in `MODE=MSSQLServer` for local runs and tests; SQL Server in production (mssql-jdbc 12.8.1) | `application*.yml`, `pom.xml` |
| Migrations | Flyway: production `V1`–`V12`, dev demo data `V10`–`V24`; every application table named `cov_*` | `db/migration`, `db/demo` |
| Formula language | Own engine: Lexer → Parser → TypeChecker → Evaluator → Renderer, with windowed aggregation and fiscal periods | `formula/` |
| External calls | Spring WebClient + resilience4j (circuit breaker, retry, time limit) | `service/impl/WebClient*`, `application.yml` |
| API docs | springdoc-openapi (`/swagger-ui.html`) | `pom.xml`, `application.yml` |
| Dev stubs | WireMock on port 9311 for every bank service; an in-app mock ML endpoint in the dev profile | `wiremock/mappings`, `application-dev.yml` |
| User identity | No Spring Security: the user is the `X-User` request header (a dev user switcher in the UI); the support API needs an `X-Support-Token` | `config/CurrentUserContext.java`, `config/SupportTokenInterceptor.java` |
| Frontend | React 18, Mantine 8, TanStack Query 5, Vite 5 (dev server 9312, proxy `/api` → 9310) | `frontend/package.json`, `vite.config.ts` |
| Quality gates | JaCoCo 95% line / 90% branch, SpotBugs + FindSecBugs, OWASP dependency-check (CVSS 7), ESLint (0 warnings), Vitest, Playwright | `CLAUDE.md`, `pom.xml` |
| Run | `start.bat`; or `mvn spring-boot:run` (port 9310) and `npm run dev` (port 9312) | `README.md` |

## 2. Component architecture

```mermaid
%%{init: {"flowchart": {"wrappingWidth": 420}}}%%
flowchart TB
    user(["RM · WRM · covenant author · support<br/>browser"])

    subgraph fe["frontend — React 18 + Mantine 8 + TanStack Query, Vite :9312"]
        direction LR
        pages["Pages<br/>covenant-master · measures · data-sources · formula<br/>onboarding wizard · monitoring By-CP / By-Covenant<br/>settings · support"]
        client["api/client.ts<br/>apiFetch over /api/v1<br/>X-User header from the dev user switcher"]
        pages --> client
    end

    ctrl["controller — 20 REST controllers /api/v1/**<br/>(support routes need X-Support-Token)"]

    subgraph be["backend — Spring Boot 3.5, :9310, package com.crisil.covenant"]
        direction TB
        subgraph author["Authoring"]
            direction LR
            lib["CovenantMasterService<br/>library, eligibility, AUTO / MANUAL"]
            ms["MeasureService · DataSourceService<br/>DataSourceTableService · ColumnDiscovery"]
        end
        subgraph onboard["Onboarding"]
            direction LR
            rec["RecommendationService<br/>OnboardingRecommendationService<br/>MlThresholdService"]
            app["AppliedCovenantService<br/>apply, per-CP overrides, audit"]
        end
        subgraph check["Checking"]
            direction LR
            sch["ScheduleService · DailyRunInitializer<br/>Quartz triggers"]
            asm["AssessmentService<br/>compile → ingest → resolve → evaluate"]
            ing["IngestionOrchestrationService<br/>StagingIngestionService<br/>freshness gate"]
            agg["MeasureAggregationResolver<br/>windows, FLAT / PERIODIC"]
            dsl["formula — FormulaEngine<br/>Lexer · Parser · TypeChecker<br/>Evaluator · Renderer"]
        end
        subgraph follow["Monitoring and follow‑up"]
            direction LR
            rev["ReviewService · MonitoringService<br/>CP grid, headroom, schedule"]
            disp["Disposition · AttachmentService<br/>waiver / extension, evidence"]
            br["BreachNotificationService<br/>RM / WRM summaries"]
            wf["WorkflowCaseService<br/>one case per CP"]
            ai["AiSuggestionService"]
            ops["Ops* services<br/>diagnose, re-ingest, re-assess, health, audit"]
        end
        jobs["Quartz jobs<br/>AssessmentJob per covenant · IngestionPreStageJob 05:30<br/>DailyBreachRunJob 06:00"]
        repo["repository — Spring Data JPA"]
        impl["service/impl — 9 WebClient clients<br/>resilience4j: circuit breaker, retry, time limit"]
    end

    db[("H2 file (dev) / SQL Server (prod)<br/>23 cov_* tables · QRTZ_* · flyway_schema_history")]

    bank{{"Bank services — WireMock :9311 in dev<br/>credit proposals (RM / WRM) · covenant attributes<br/>data API + data catalogue · ML thresholds<br/>AI status suggestions · notification · workflow engine · file service"}}
    mssql{{"Customer SQL Server tables<br/>(MSSQL data sources)"}}

    user --> fe
    client --> ctrl
    ctrl --> author & onboard & follow
    ctrl -->|"/assessments/run"| asm
    app --> sch --> jobs
    jobs --> asm & br
    jobs -->|05:30| ing
    asm --> dsl
    asm --> ing --> agg
    asm --> wf
    author & onboard & check & follow --> repo --> db
    rec & br & wf & disp & ai & ing --> impl
    impl --> bank
    ing --> mssql
```

## 3. Scheduled covenant check (one covenant)

```mermaid
sequenceDiagram
    autonumber
    participant Q as Quartz (AssessmentJob)
    participant A as AssessmentService
    participant W as WorkflowCaseService
    participant F as FormulaEngine
    participant I as IngestionOrchestrationService
    participant S as Source (REST API or SQL Server)
    participant R as MeasureAggregationResolver
    participant DB as cov_* tables

    Q->>A: runAssessment(appliedCovenantId, SCHEDULED)
    A->>W: ensureCase(cpId) — open the CP's workflow case
    alt MANUAL covenant
        A->>DB: assessment PENDING_MANUAL (a person records the result)
    else AUTO covenant
        A->>F: compile(condition, measure catalogue)
        alt formula error
            A->>DB: ERROR "Formula error"
        end
        loop each source table the formula needs
            A->>I: ingestIfStale(table, entity, from, asOf)
            I->>I: freshness gate: missing / stale / source changed / 24 h TTL
            opt copy needs refreshing
                I->>S: read the CP's (or obligor's) series
                I->>DB: write cov_staging_value, update cov_ingestion_state
            end
        end
        A->>R: resolve values for every term (windows, FLAT / PERIODIC)
        R->>DB: read cov_staging_value
        alt a value is missing
            A->>DB: ERROR "Missing values for measures"
        else all present
            A->>F: evaluate → true = COMPLIANT, false = BREACHED
            A->>DB: append cov_assessment (result, value, expression, data snapshot)
        end
    end
```

## 4. Daily timetable

```mermaid
gantt
    title What runs by itself (server time)
    dateFormat HH:mm
    axisFormat %H:%M
    section Per covenant
    Each covenant's own AssessmentJob (default crons, all at 2 am) :a1, 02:00, 30m
    section Portfolio
    IngestionPreStageJob — copy figures for covenants due soon :a2, 05:30, 20m
    DailyBreachRunJob — assess due covenants, e-mail RM cc WRM :a3, 06:00, 30m
```

In this original version every covenant of a frequency used the same default cron, so thousands of
checks started in the same second at 02:00 (fixed later in covenant-platform, BUG-08).

## 5. The formula language engine

```mermaid
%%{init: {"flowchart": {"wrappingWidth": 420}}}%%
flowchart LR
    src["Condition text<br/>e.g. TOTAL_DEBT / EBITDA <= 3.5<br/>AVG(EBITDA, 4Q) >= 1000000"] --> lex["Lexer<br/>tokens"]
    lex --> par["Parser<br/>syntax tree (Node)"]
    par --> tc["FormulaTypeChecker<br/>types, measures exist,<br/>boolean result"]
    tc --> cf["CompiledFormula<br/>terms + windows + sampling"]
    cf --> bind["FormulaMeasureBinder<br/>terms → measures → tables"]
    bind --> res["MeasureAggregationResolver<br/>values per term"]
    res --> ev["FormulaEvaluator<br/>COMPLIANT / BREACHED"]
    cf --> rnd["FormulaRenderer<br/>'3.2 / 1.1 <= 3.5' for the grid"]
    cat[("MeasureCatalog<br/>cov_measure")] --> tc
```

## 6. Data model (main tables)

```mermaid
erDiagram
    cov_covenant_master ||--o{ cov_applied_covenant : "applied to a CP"
    cov_covenant_master ||--o{ cov_expression_change_log : "rule history"
    cov_applied_covenant ||--o{ cov_assessment : "one row per check"
    cov_applied_covenant ||--|| cov_monitoring_schedule : "Quartz schedule"
    cov_applied_covenant ||--o{ cov_applied_covenant_audit : "definition changes"
    cov_applied_covenant ||--o{ cov_covenant_disposition : "waiver / extension"
    cov_applied_covenant ||--o{ cov_covenant_attachment : "evidence"
    cov_applied_covenant ||--o{ cov_covenant_status_audit : "status history"
    cov_assessment ||--o{ cov_assessment_threshold : "decomposed comparisons"
    cov_data_source ||--o{ cov_data_source_table : "has tables"
    cov_data_source_table ||--o{ cov_measure : "columns or codes"
    cov_data_source_table ||--o{ cov_staging_value : "staged copy"
    cov_data_source_table ||--o{ cov_ingestion_state : "freshness per entity"
    cov_credit_proposal_cache ||--o{ cov_applied_covenant : "CP"
    cov_obligor_cp_map }o--|| cov_credit_proposal_cache : "obligor of CP"

    cov_covenant_master {
        string id PK
        string name
        string category "FINANCIAL / NON_FINANCIAL"
        string assessment_type "AUTO / MANUAL"
        string condition_expression
        string default_frequency
    }
    cov_applied_covenant {
        string id PK
        string cp_id
        string covenant_master_id FK
        string condition_expression "per-CP override"
        string threshold_source "MASTER / USER / ML"
        string status "ACTIVE / SUSPENDED"
    }
    cov_assessment {
        string id PK
        string applied_covenant_id FK
        string result "COMPLIANT / BREACHED / NA / ERROR / PENDING_REVIEW"
        string evaluated_expression
        string data_snapshot_json
        datetime executed_at
    }
    cov_staging_value {
        string data_source_table_id FK
        string entity_key "CP or obligor"
        string item_code
        date as_of_date
        decimal num_value
    }
```

Other tables: `cov_covenant_recommendation`, `cov_threshold_audit`, `cov_compliance_certificate`,
`cov_notification_audit`, `cov_ops_audit`, `cov_reporting_setting` (23 `cov_*` tables in all).

## 7. REST API map (`/api/v1`)

| Area | Endpoints |
|---|---|
| Covenant library | `/covenants` (CRUD, `/{id}/audit`, `/{id}/usage`) |
| Measures and sources | `/measures`, `/data-sources` (`/{id}/test`, `/{id}/columns`), `/data-sources/{id}/tables` (`/columns`, `/code-values`) |
| Formula | `/formulas/validate`, `/formulas/metadata`, `/formulas/thresholds`, `/formulas/apply-thresholds`, `/schedules/validate-cron` |
| Onboarding | `/credit-proposals/{cpId}` (`/recommendations`, `/onboarding-recommendations`, `/custom-covenants`, `/applied-covenants`, `/ml-threshold-recommendations`) |
| Applied covenants | `/applied-covenants/{id}` (PATCH, `/audit`, `/definition-history`) |
| Assessments | `/assessments/run`, `/assessments`, `/assessments/{id}`, `/{id}/complete-manual` |
| Monitoring | `/monitoring/summary`, `/schedule`, `/cps/{cpId}/covenants`, `/cp/{cpId}/review`, status, status-audit, attachments, cycles, `/notification-runs` |
| Settings | `/settings/reporting` |
| Support (`X-Support-Token`) | `/ops/cps/{cpId}/diagnostics`, `/staging`, `/ingestion-state`, `/reingest`, `/data`; `/ops/applied-covenants/{id}/reassess`; `/ops/health/data-sources`; `/ops/audit` |

## 8. What it did well, and what it lacked

| Did well | Lacked for our purpose |
|---|---|
| Real database, migrations, append-only audit everywhere | Could not read documents: every threshold and figure came from bank systems or was typed in |
| Covenant library, eligibility rules, formula language with windows and fiscal periods | No AI reading of agreements or statements |
| Quartz scheduling, staging with a freshness gate, support toolkit | No real sign-in: the user was a request header |
| Strict quality gates (95% coverage, SpotBugs, OWASP) | Scheduler not clustered; all default checks fired in one second |

This service became **Core** in covenant-platform; see [covenant-platform.md](covenant-platform.md).
