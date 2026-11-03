# Backend Developer Guide — Covenant Monitoring System

> **Audience:** A Java / Spring Boot developer joining the backend team. This is a knowledge-transfer
> (KT) document: read it top-to-bottom once, then use it as a reference. It explains the technology,
> the package layout, how a request flows through the layers, the domain model, the formula engine,
> the scheduling/assessment machinery, external integrations, and how to build/test/run locally.

For a product-level overview of *what* the system does, see the root [`README.md`](../README.md).
For the per-feature specifications, see [`_userstories/`](../_userstories/README.md).

---

## Table of contents

1. [What this service is](#1-what-this-service-is)
2. [Technology stack](#2-technology-stack)
3. [Get it running in 10 minutes](#3-get-it-running-in-10-minutes)
4. [Package architecture (layered)](#4-package-architecture-layered)
5. [How a request flows through the layers](#5-how-a-request-flows-through-the-layers)
6. [Domain model & database](#6-domain-model--database)
7. [The formula engine (deep dive)](#7-the-formula-engine-deep-dive)
8. [Recommendation & applied-covenant flow](#8-recommendation--applied-covenant-flow)
9. [Assessment engine & scheduling](#9-assessment-engine--scheduling)
10. [RM review, disposition & audit](#10-rm-review-disposition--audit)
11. [External integrations & resilience](#11-external-integrations--resilience)
12. [Configuration & profiles](#12-configuration--profiles)
13. [Cross-cutting concerns](#13-cross-cutting-concerns)
14. [Build, test & quality gates](#14-build-test--quality-gates)
15. [How to add a new feature (recipes)](#15-how-to-add-a-new-feature-recipes)
16. [Gotchas & conventions to memorise](#16-gotchas--conventions-to-memorise)

---

## 1. What this service is

`covenant-service` is a single Spring Boot application that lets a bank:

- **author** reusable *covenant* rule templates and the *measures* (financial metrics) they reference;
- register **data sources** (external tables / key-value feeds) that supply the metric time-series;
- **recommend** and **apply** covenants to *Credit Proposals* (CPs) with per-CP threshold overrides;
- **schedule** and **run** compliance *assessments* against live data, on a cadence (Quartz);
- **notify** Relationship Managers (RMs) of breaches, let them **review** each CP, record a
  **disposition** (waiver / extension), attach **evidence**, and pull **AI status suggestions**;
- keep an **append-only audit trail** of every threshold and status change with provenance.

A covenant condition is not SpEL — it is written in a small **measure-based formula DSL** (e.g.
`AVG(PAT, '2y') > 100000000`) that is lexed, parsed, type-checked, and evaluated against windowed
time-series data. The formula engine is the technical heart of the system.

---

## 2. Technology stack

| Concern | Choice | Notes |
|---|---|---|
| Language / JDK | Java 17 | `maven.compiler.source/target = 17` |
| Framework | Spring Boot **3.5.14** | Spring Framework 6.2, Hibernate 6.6, Jakarta EE (`jakarta.*`) |
| Web | Spring MVC (`spring-boot-starter-web`) | REST controllers, `@RestControllerAdvice` |
| Reactive client | Spring WebFlux `WebClient` | only for *outbound* calls to external services |
| Persistence | Spring Data JPA + Hibernate | `ddl-auto: validate` — schema is owned by Flyway, not Hibernate |
| DB (dev/test) | **H2** in `MODE=MSSQLServer` | file-backed at `backend/data/covenant` |
| DB (prod) | **SQL Server** | `mssql-jdbc` driver at `runtime` scope |
| Migrations | **Flyway** (+ `flyway-sqlserver`) | `classpath:db/migration` |
| Scheduling | **Quartz** JDBC job store | `QRTZ_*` tables, MSSQL delegate |
| Resilience | **resilience4j** 2.2.0 | circuit breaker + retry + time limiter on every external call |
| API docs | **springdoc-openapi** 2.8.9 | `/v3/api-docs`, `/swagger-ui.html` |
| Validation | `spring-boot-starter-validation` | Jakarta Bean Validation on request DTOs |
| Test | JUnit 5, Spring Boot Test, **WireMock** 3.9.2 | WireMock stubs the external services in tests + dev |
| Coverage | **JaCoCo** 0.8.12 | gate: 0.95 line / 0.90 branch |
| Static analysis | **SpotBugs** 4.8.6 + **FindSecBugs** 1.13.0 | Max effort / Low threshold |
| Dependency CVEs | **OWASP dependency-check** | `failBuildOnCVSS 7` |

---

## 3. Get it running in 10 minutes

Nothing is on `PATH` in this workspace, so use absolute tool paths and set `JAVA_HOME` before Maven.
The repo also ships `build.bat`, `start.bat`, and `stop.bat` at the root which wrap this for you.

```bash
# from backend/
export JAVA_HOME=/c/Users/<you>/Documents/jdk-17
MVN=/c/Users/<you>/Documents/apache-maven/bin/mvn

# fast inner loop — unit tests only (skips ITs, SpotBugs, OWASP)
"$MVN" -P fast test -f pom.xml

# full local gate WITHOUT the NVD network download (JaCoCo + SpotBugs + ITs)
"$MVN" -P no-owasp verify -f pom.xml

# run the app with dev demo data (port 9310, H2 console at /h2-console)
"$MVN" -P fast "-Dspring-boot.run.profiles=dev" spring-boot:run -f pom.xml
```

Once up:
- Swagger UI → <http://localhost:9310/swagger-ui.html>
- OpenAPI JSON → <http://localhost:9310/v3/api-docs>
- H2 console → <http://localhost:9310/h2-console> (JDBC URL is in `application.yml`)
- Actuator → <http://localhost:9310/actuator>

**Ports:** backend `9310`; the WireMock external stubs run on `9311`/`9312`/`9313` in dev
(see [§11](#11-external-integrations--resilience)); the frontend dev server is `9312` and proxies
`/api` → `9310`.

---

## 4. Package architecture (layered)

The backend was **restructured from feature-packages to technical/layered packages**. Everything
lives under `com.crisil.covenant`. `CovenantApplication` (the `@SpringBootApplication` entry point)
sits at the root so component/entity scanning covers all sub-packages.

```
com.crisil.covenant
├── CovenantApplication.java         # entry point (@SpringBootApplication at root)
│
├── config/        (6)   Spring @Configuration & cross-cutting beans
│                        AuditingConfig, ClientsProperties, CurrentUserContext,
│                        OpenApiConfig, WebClientsConfig, WireMockDevConfig
│
├── controller/    (14)  @RestController HTTP entry points (one per resource area)
│                        CovenantMasterController, MeasureController, DataSourceController,
│                        DataSourceTableController, RecommendationController,
│                        AppliedCovenantController, AssessmentController, MonitoringController,
│                        CovenantAssessmentController (RM review), MlThresholdController,
│                        MockMlThresholdController, NotificationRunController,
│                        ReportingSettingsController, CronValidationController
│
├── service/       (32)  Business logic (interfaces, @Service beans, jobs, client interfaces)
│   └── impl/      (9)   WebClient* implementations of the external-client interfaces
│
├── repository/    (19)  Spring Data JPA repositories (one per aggregate/audit table)
│
├── entity/        (20)  @Entity JPA classes (mapped to cov_* tables) + AuditableEntity base
│
├── domain/        (14)  Non-JPA domain/read models & external-payload records
│                        (CpEligibility, CovenantOnCp, CpMonitoringSummary, MonitoringEvent,
│                        CreditProposalDetails, DataCatalogResponse, …)
│
├── dto/           (44)  Wire records: *Request / *Response / *Dto (coverage-excluded)
│
├── enums/         (18)  Domain enums (AssessmentResult, Frequency, ThresholdSource,
│                        CovenantDispositionStatus, CovenantStatusSource, …)
│
├── exception/     (5)   ApiException, ResourceNotFoundException, DuplicateResourceException,
│                        WorkflowException, GlobalExceptionHandler (@RestControllerAdvice)
│
├── util/          (7)   Stateless helpers & JPA converters
│                        (HeadroomCalculator, MonitoringTimingCalculator,
│                        CovenantMasterSpecifications, JdbcDataSourceFactory, SqlIdentifiers,
│                        StringListConverter, StringMapConverter)
│
└── formula/       (30)  The formula DSL engine — kept COHESIVE, not split by layer
    └── web/       (8)   FormulaController + its request/response records
```

**Why `formula.*` stays cohesive:** the engine is a self-contained compiler pipeline (lexer →
parser → AST → type-checker → evaluator/renderer) whose classes only make sense together. Splitting
it across `service/`, `dto/`, `util/` would obscure that. It is the one intentional exception to the
layer split.

### The layering rule (memorise this)

```
controller  →  service (interface)  →  repository  →  entity
     │              │
     │              └→ service.impl (WebClient*)  →  external HTTP
     ↓
    dto  (request/response only — never leak entities over the wire)
```

- **Controllers** are thin: validate input DTO, call a service, map to a response DTO. No business
  logic, no repository access.
- **Services** own transactions and business rules. External-service *interfaces* (e.g.
  `NotificationClient`, `FileUploadClient`) live in `service/`; their `WebClient*` implementations
  live in `service/impl/`. This lets tests inject a fake client without touching HTTP.
- **Repositories** are Spring Data interfaces; custom queries use `@Query` or `Specification`s
  (`CovenantMasterSpecifications` in `util/`).
- **Entities** map to `cov_*` tables and extend `AuditableEntity` where auditing applies.
- **DTOs** are the only types crossing the HTTP boundary; they live in `dto/` (and `formula/web/`
  for the formula endpoints) and are **excluded from coverage**.

---

## 5. How a request flows through the layers

Take **"apply a covenant to a CP"** as a worked example:

1. `AppliedCovenantController` receives `POST /api/v1/applied-covenants` with a validated request DTO.
2. It delegates to `AppliedCovenantService`, which:
   - loads the `CovenantMaster` (via `CovenantMasterRepository`),
   - validates/normalises the per-CP condition override through the **formula engine**
     (`FormulaEngine.validate(...)` + `applyThresholds(...)`),
   - persists an `AppliedCovenant` entity (mapped to `cov_applied_covenant`),
   - writes an `AppliedCovenantAudit` row and, if the threshold changed, a `ThresholdAudit` row
     with `ThresholdSource` provenance,
   - (re)computes a `MonitoringSchedule` so the covenant is picked up by the assessment job.
3. Any `ResourceNotFoundException` / `DuplicateResourceException` / validation error bubbles up to
   `GlobalExceptionHandler`, which converts it to the shared `ErrorResponse` schema
   (`code, message, details[], timestamp`) with the right HTTP status.
4. The controller returns a response DTO; springdoc has already documented the 400/404/500 error
   responses for this operation via `OpenApiConfig`.

Every write that changes a threshold or a status appends to an **append-only audit table** — this is
a core compliance requirement, not optional.

---

## 6. Domain model & database

### 6.1 Table naming — the `cov_` rule

**Every application/audit table MUST be named `cov_*`.** The `@Table(name=...)` on each entity must
match its Flyway migration exactly, because Hibernate runs with `ddl-auto: validate` (it will fail
to boot on any mismatch). Examples: `cov_covenant_master`, `cov_measure`, `cov_data_source`,
`cov_applied_covenant`, `cov_assessment`, `cov_monitoring_schedule`, `cov_covenant_disposition`,
`cov_covenant_status_audit`, `cov_covenant_attachment`, `cov_compliance_certificate`.

**Not** `cov_`-prefixed (intentionally): the 11 Quartz `QRTZ_*` tables, Flyway's
`flyway_schema_history`, and the dev-only demo table `cp_metrics_demo` (it simulates an *external*
customer data source, so it is not part of our schema).

### 6.2 Key entities (`entity/`)

| Entity | Table | Role |
|---|---|---|
| `CovenantMaster` | `cov_covenant_master` | reusable covenant template (name, category, condition, frequency) |
| `Measure` | `cov_measure` | a named financial metric usable in formulas |
| `DataSource` / `DataSourceTable` | `cov_data_source` / `cov_data_source_table` | registered external data feed + its column/table metadata |
| `AppliedCovenant` | `cov_applied_covenant` | a covenant instance bound to a CP (with per-CP overrides) |
| `Assessment` | `cov_assessment` | **append-only** result of one evaluation (result, computedValue, condition snapshot) |
| `MonitoringSchedule` | `cov_monitoring_schedule` | when the next assessment is due for an applied covenant |
| `CovenantRecommendation` | `cov_covenant_recommendation` | engine-produced suggestions for a CP |
| `CovenantDisposition` | `cov_covenant_disposition` | RM decision on a breach (waiver/extension), updated in place |
| `CovenantStatusAudit` | `cov_covenant_status_audit` | **append-only** provenance for every status change |
| `ThresholdAudit` | `cov_threshold_audit`-family | **append-only** provenance for threshold changes (MASTER/USER/ML) |
| `CovenantAttachment` / `ComplianceCertificate` | `cov_covenant_attachment` / `cov_compliance_certificate` | evidence & certificate file references |
| `AuditableEntity` | — (`@MappedSuperclass`) | `createdBy/at`, `modifiedBy/at` populated by Spring Data auditing |

**Immutability rule:** `cov_assessment`, `cov_covenant_status_audit`, and the threshold/notification
audit tables are **append-only**. Never add setters or update queries to those entities.

### 6.3 Flyway migrations

- Prod migrations: `src/main/resources/db/migration` (`V1`, `V3`, … `V35`).
- Dev-only demo seed: `src/main/resources/db/demo` (loaded only under the `dev` profile).
- **Never edit a released migration** — always add the next `Vnn__*.sql`.
- DDL must run on **both SQL Server and H2 (`MODE=MSSQLServer`)**: use `IDENTITY`, `DATETIME2`, `BIT`,
  and **named** PK/UK/CK/FK constraints; put domain CHECK constraints on enum-backed columns.

---

## 7. The formula engine (deep dive)

Located entirely in `formula/`. This is a **hand-written compiler**, not SpEL and not a scripting
language. Understanding it is the single most valuable thing for a backend dev here.

### 7.1 Pipeline

```
raw expression string
   │  Lexer            → List<Token>          (TokenType, Token)
   ▼
   │  Parser           → Node (AST)           (BinaryOperator, UnaryOp, FunctionKind, Window…)
   ▼
   │  FormulaTypeChecker → FormulaType        (validates operand types, window units, measures)
   ▼
   ├─ FormulaEvaluator → boolean/number       (evaluates AST against time-series values)
   └─ FormulaRenderer  → human-readable text  (for the UI / condition snapshots)
```

- **`FormulaEngine`** is the public façade — this is what services call. Key public methods:
  - `validate(expression)` → `FormulaValidationResult`
  - `thresholds(expression)` → `List<FormulaThreshold>` (literal value + operator + tracked expr)
  - `applyThresholds(...)` → produce an edited expression with new threshold literals
  - `compile(expression)` → `CompiledFormula` for evaluation
  - metadata for the UI builder via `MeasureCatalog` / `MeasureMeta`
- **`MeasureCatalog` / `MeasureMeta`** describe which measures and functions are legal, so the
  builder UI and the type checker agree.
- **Errors** are structured: `FormulaException` / `FormulaSyntaxException` carry an `ErrorCode` and
  `FormulaError` so the UI can point at the offending token.

### 7.2 The DSL itself

- **Measures** are bare identifiers (e.g. `PAT`, `EBITDA`) resolved against registered `Measure`s.
- **Aggregation functions** (`FunctionKind`) operate over a **time window**: `AVG(PAT, '2y')`,
  `SUM(REVENUE, '4q')`, `MIN(...)`, `MAX(...)`, etc.
- **Windows** (`Window`, `WindowUnit`) express the look-back: `'2y'`, `'4q'`, `'12m'`. Sampling
  behaviour is governed by `SamplingMode` (FLAT vs PERIODIC) and `SamplingKeyword`, with fiscal-year
  alignment and `PeriodInclusion` semantics.
- **Comparisons** (`BinaryOperator`) yield the covenant condition: `>`, `>=`, `<`, `<=`, `==`, `!=`,
  combined with `AND`/`OR`.
- **Semantics that drive everything downstream:** a covenant condition that evaluates **`true` ⇒
  COMPLIANT**. The assessment engine stores the machine verdict; the UI derives the human status
  from it (see [§10](#10-rm-review-disposition--audit)).

### 7.3 Headroom

`util/HeadroomCalculator` reuses `FormulaEngine.thresholds(...)` + the latest assessment's
`computedValue` to compute a **signed distance in the compliant direction**:
- upper-bound (`<`, `<=`) ⇒ `threshold − actual`
- lower-bound (`>`, `>=`) ⇒ `actual − threshold`

Positive = cushion, negative = breach size. Only defined for a **single numeric comparison**;
compound / equality / non-numeric conditions return `null` (UI shows "—").

### 7.4 Formula HTTP surface

`formula/web/FormulaController` exposes validate / thresholds / apply-thresholds / metadata
endpoints so the frontend `FormulaBuilder` can validate and preview expressions live.

---

## 8. Recommendation & applied-covenant flow

- **Eligibility:** `CpEligibility.from(CovenantAttributes)` (in `domain/`) projects the external
  Attributes-API payload into the dimensions the engine matches on (segment, industry, country of
  risk/incorporation, CP type, legal entity type, facility/collateral types, Islamic-banking flag).
  A missing attribute is left blank and **fails any non-wildcard covenant dimension**.
- **`RecommendationService`** matches eligible `CovenantMaster`s against a CP's eligibility and
  persists `CovenantRecommendation`s (`RecommendationStatus`: pending/accepted/rejected).
- **`AppliedCovenantService`** turns an accepted recommendation (or a manual pick) into an
  `AppliedCovenant`, allowing a per-CP `conditionExpression` override and threshold edits — each
  audited via `AppliedCovenantAudit` + `ThresholdAudit`.

Threshold provenance uses `ThresholdSource`: `MASTER` (template default), `USER` (human edit),
`ML` (model-recommended, see `MlThresholdService` / `MlThresholdRecommender`).

---

## 9. Assessment engine & scheduling

### 9.1 Assessment

- `AssessmentService` evaluates an applied covenant's effective condition against data pulled from
  its registered data source (`DataApiClient` / columnar tables or key-value feed), using the
  compiled formula, and writes an **append-only** `Assessment` row: `result`
  (`AssessmentResult`: COMPLIANT / BREACHED / NA / ERROR / PENDING_REVIEW), `computedValue`,
  and a **snapshot of the condition** as it was at run time (so history is reproducible even after
  later edits).
- Assessments are `AUTO` (scheduled) or `MANUAL` (`AssessmentType` / `AssessmentTrigger`). The
  single-covenant manual path lets an RM record COMPLIANT/BREACHED/NA + a note.

### 9.2 Scheduling (Quartz)

- Quartz uses the **JDBC job store** (`QRTZ_*` tables, MSSQL delegate) so jobs survive restarts and
  can cluster.
- `ScheduleService` maintains `MonitoringSchedule` rows (next-due dates) per applied covenant, using
  `Frequency` + `MonitoringTimingCalculator` (in `util/`) to compute timing/`TimingStatus`.
- **`AssessmentJob`** runs per-covenant assessments when due.
- **`DailyBreachRunJob`** sweeps the whole portfolio daily, produces a `CpMonitoringSummary` per CP,
  and triggers breach notifications; `DailyRunInitializer` wires up the recurring trigger.

### 9.3 Monitoring read models

`MonitoringService` builds the read-only views the UI consumes: `CpMonitoringSummary` (per-CP counts),
`MonitoringEvent`/agenda (overdue / due-soon / upcoming buckets), and the per-CP covenant list
(`CovenantOnCp`). These reuse the timing helpers rather than duplicating date math.

---

## 10. RM review, disposition & audit

This is the newest area (`CovenantAssessmentController` + `ReviewService` + related entities).

- **Derive-from-result principle:** the screen never inverts the engine. It maps the stored
  `AssessmentResult` → a human status: `BREACHED ⇒ Breached`; `COMPLIANT`/`NA ⇒ Complied`.
- **Disposition** (`CovenantDisposition`, one row per `cpId + appliedCovenantId`, updated in place):
  `status` (`CovenantDispositionStatus`: COMPLIED / BREACHED / WAIVER / EXTENSION), optional
  `extensionDate`, `statusSource` (`CovenantStatusSource`: SYSTEM / USER / AI), `note`.
  - Editing is **enabled only when the latest result is BREACHED**. Complied rows are locked.
  - On a breach the RM may set **Waiver** or **Extension** only; **Extension requires a date**.
- **`CovenantStatusAudit`** (append-only, mirrors `ThresholdAudit`) records every change:
  old/new status, old/new extension date, source, rationale, `changedBy`, `changedAt`.
- **Evidence:** `AttachmentService` forwards files through `FileUploadClient` to the external
  file-upload service and stores the returned `fileId` in `cov_covenant_attachment` (per covenant)
  or `cov_compliance_certificate` (per CP).
- **AI suggestions:** `AiSuggestionService` calls `AiStatusRecommender` with covenant data + uploaded
  fileIds and returns a recommended status per covenant; accepting one writes a disposition with
  source `AI`.

The disposition layer **never mutates** `cov_assessment` or the US-MON-14 dashboard counts.

---

## 11. External integrations & resilience

All outbound HTTP goes through **`WebClient`**, wrapped in resilience4j (circuit breaker + retry +
time limiter) and designed to **degrade gracefully rather than throw**. The pattern is always the
same: an **interface in `service/`** + a **`WebClient*` implementation in `service/impl/`**.

| Interface (`service/`) | Impl (`service/impl/`) | Talks to |
|---|---|---|
| `CreditProposalClient` | `WebClientCreditProposalClient` | CP master data |
| `CovenantAttributesClient` | `WebClientCovenantAttributesClient` | CP eligibility attributes |
| `DataApiClient` | `WebClientDataApiClient` | metric time-series values |
| `DataCatalogClient` | `WebClientDataCatalogClient` | data-source column/table discovery |
| `NotificationClient` | `WebClientNotificationClient` | breach emails |
| `WorkflowClient` | `WebClientWorkflowClient` | workflow kickoff |
| `MlThresholdRecommender` | `WebClientMlThresholdRecommender` | ML threshold suggestions |
| `FileUploadClient` | `WebClientFileUploadClient` | evidence / certificate upload (multipart) |
| `AiStatusRecommender` | `WebClientAiStatusRecommender` | AI status suggestions |

- Beans and base-URLs are wired in `config/WebClientsConfig` + `config/ClientsProperties` from the
  `covenant.clients.*` block in `application.yml`.
- resilience4j instances are configured per client under
  `resilience4j.{circuitbreaker,retry,timelimiter}.instances.*`.
- In **dev/test** the externals are faked by **WireMock** (`src/main/resources/wiremock/mappings/*`,
  wired by `WireMockDevConfig`), so you can run the whole app offline.

**When adding a new external call:** clone `WebClientNotificationClient` (the canonical example) —
interface in `service/`, impl in `service/impl/`, DTOs in `dto/`, resilience4j operators on the
`Mono`, a fallback that returns empty/degraded rather than throwing, plus a WireMock mapping and a
WireMock-backed client test.

---

## 12. Configuration & profiles

- **`application.yml`** — base config: datasource (H2 file, MSSQL mode), JPA (`ddl-auto: validate`,
  `open-in-view: false`, UTC), Flyway, Quartz JDBC store, server port `9310`, springdoc paths, and
  the `covenant.clients.*` external base-URLs + resilience4j blocks.
- **`application-dev.yml`** — dev overrides: points clients at the local WireMock ports, enables the
  demo seed.
- **`application-test.yml`** — test overrides for the integration tests.
- **Profiles:** run with `-Dspring-boot.run.profiles=dev` for local work. No profile = the plain base
  config (prod-like, expects real external URLs and a real SQL Server datasource).

---

## 13. Cross-cutting concerns

- **Auditing:** `config/AuditingConfig` enables JPA auditing; `config/CurrentUserContext` supplies the
  "who" for `AuditableEntity` and the audit tables.
- **Error handling:** `exception/GlobalExceptionHandler` (`@RestControllerAdvice`) maps every
  exception to the shared `ErrorResponse` schema. `OpenApiConfig` documents 400/404/500 on every
  operation (404 only where the path has a `{var}`) so controllers only annotate success responses.
- **OpenAPI:** `config/OpenApiConfig` registers the `ErrorResponse` schema once and injects the common
  error responses across all operations.
- **JPA converters:** `util/StringListConverter` and `util/StringMapConverter` persist `List`/`Map`
  attributes as delimited/JSON strings.
- **Dynamic JDBC:** `util/JdbcDataSourceFactory` + `util/SqlIdentifiers` build safe, quoted SQL for
  querying externally-registered data-source tables (identifier sanitisation matters here for
  security — FindSecBugs watches this).

---

## 14. Build, test & quality gates

### Commands

```bash
"$MVN" -P fast test          # unit tests only — the inner loop
"$MVN" -P no-owasp verify    # full gate minus the NVD network download (use this before pushing)
"$MVN" verify                # full gate incl. OWASP dependency-check (needs NVD_API_KEY / network)
```

Maven profiles (from `pom.xml`): `fast` (skips ITs + SpotBugs + OWASP), `no-owasp` (skips only the
dependency-check network step).

### Gates that WILL fail your build

| Gate | Threshold | Notes |
|---|---|---|
| **JaCoCo** | 0.95 line / 0.90 branch (BUNDLE) | DTOs (`dto/**`, `*Request`, `*Response`, `*Dto`), `config/**`, `CovenantApplication` are excluded. Put wire records in `dto/`. |
| **SpotBugs + FindSecBugs** | Max effort, Low threshold, `failOnError` | include/exclude filters in `spotbugs-include.xml` / `spotbugs-exclude.xml` (per-class FQN exclusions). |
| **OWASP dependency-check** | `failBuildOnCVSS 7` | suppressions in `owasp-suppressions.xml`; needs NVD access. |

### Testing layers

- **Unit tests** mirror their subject into the same layer package (e.g. a test for a `service/` class
  lives in `service/` under `src/test`). This preserves package-private access.
- **Integration tests** are named `*IT.java` and run under failsafe during `verify` (WireMock-backed;
  e.g. Quartz durability, schedule persistence).
- **Client tests** hit WireMock: assert 2xx → parsed result, and timeout/5xx → graceful fallback.

---

## 15. How to add a new feature (recipes)

**Add a REST endpoint on an existing resource:**
1. Add request/response records in `dto/`.
2. Add the method to the relevant `service/` interface/impl (transaction + business rules there).
3. Add the controller method (thin: validate → service → map to response DTO).
4. Add unit tests in the mirrored `service/` test package; add a controller/web-layer test if useful.

**Add a new persisted table:**
1. Write the next `Vnn__*.sql` in `db/migration` (MSSQL+H2 compatible, `cov_` prefix, named
   constraints, domain CHECKs on enum columns). Never edit an old migration.
2. Add the `@Entity` in `entity/` with `@Table(name="cov_…")` matching the DDL exactly (extend
   `AuditableEntity` if it needs who/when). If it is an audit table, make it append-only (all-args
   ctor + getters, no setters).
3. Add the `repository/` interface.
4. Because `ddl-auto: validate`, boot the app once to confirm the mapping matches the DDL.

**Add a new external integration:** see the pattern in [§11](#11-external-integrations--resilience).

---

## 16. Gotchas & conventions to memorise

- **`ddl-auto: validate`** — entity mapping and Flyway DDL must match byte-for-byte or the app won't
  boot. This is your friend: it catches drift immediately.
- **Append-only tables** — never add setters/updates to `Assessment`, `*Audit` entities.
- **`cov_` prefix** on every application/audit table; QRTZ_/flyway_/demo tables are the only
  exceptions.
- **CRLF-safe logging (SpotBugs `CRLF_INJECTION_LOGS`)** — log with **parameterized, ≤2-arg,
  constant templates** and inline-sanitise any user/external value:
  `log.info("cp={}", cpId.replace('\n','_').replace('\r','_'))`. A shared `sanitise()` helper is
  **not** recognised by FindSecBugs — inline the `.replace(...)`.
- **DTOs live in `dto/`** (and `formula/web/`) — they are coverage-excluded, so business logic must
  never hide inside a DTO.
- **External clients degrade, never throw** — a dead external URL must produce an empty/fallback
  result and a clear error, not a 500 that corrupts state.
- **`formula.*` is not layered** — treat it as one cohesive engine; call it via `FormulaEngine`.
- **Read the user story first** — `_userstories/` is the source of truth for behaviour; the code
  follows the ACs.
```
