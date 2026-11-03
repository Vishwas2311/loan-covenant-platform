# Covenant Monitoring System

A corporate-credit **covenant monitoring platform**. It maintains a reusable library of covenant rules
(financial and non-financial), recommends and applies them per **Credit Proposal** (`CP_ID`), evaluates
compliance on a schedule against real time-series customer data, and gives Relationship Managers a daily
action workspace to review breaches, record dispositions (Waiver/Extension), attach evidence, and pull
AI-assisted status suggestions — with full provenance and append-only audit trails throughout.

- **Backend** — Java 17, Spring Boot 3.5.14, Spring Data JPA (Hibernate 6.6), Quartz, Flyway, WebFlux
  `WebClient` + resilience4j, springdoc OpenAPI. Runs on H2 in `MODE=MSSQLServer` for dev/test and on
  Microsoft SQL Server in prod.
- **Frontend** — React 18, Mantine v8, TanStack Query + (Mantine) React Table, native `fetch` (no axios),
  Vite 5, TypeScript 5.
- **Rule engine** — a purpose-built **measure-based formula DSL** (not SpEL) with windowed, period-aware
  aggregation over time series.

---

## Table of contents

1. [What the system does](#what-the-system-does)
2. [Domain concepts](#domain-concepts)
3. [Architecture & project layout](#architecture--project-layout)
4. [Feature guide — how each feature works](#feature-guide)
   - [Covenant library, eligibility & assessment type](#1-covenant-library-eligibility--assessment-type)
   - [Measures & data sources (columnar + key-value)](#2-measures--data-sources)
   - [The formula DSL](#3-the-formula-dsl)
   - [Recommendation engine](#4-recommendation-engine)
   - [CP onboarding, applied covenants & ML thresholds](#5-cp-onboarding-applied-covenants--ml-thresholds)
   - [Scheduling & monitoring visibility](#6-scheduling--monitoring-visibility)
   - [The assessment engine](#7-the-assessment-engine)
   - [Daily breach run & RM/WRM notifications](#8-daily-breach-run--rmwrm-notifications)
   - [RM assessment workspace, grid & disposition](#9-rm-assessment-workspace-grid--disposition)
   - [Evidence attachments & compliance certificate](#10-evidence-attachments--compliance-certificate)
   - [AI status suggestions](#11-ai-status-suggestions)
   - [Status provenance & audit](#12-status-provenance--audit)
5. [Walkthrough: wire an external API into covenants (frontend)](#walkthrough-wire-an-external-api-into-covenants-frontend)
6. [REST API reference](#rest-api-reference)
7. [External integrations, resilience & WireMock](#external-integrations-resilience--wiremock)
8. [Data model](#data-model)
9. [Running locally](#running-locally)
10. [Quality gates & testing](#quality-gates--testing)
11. [Further documentation](#further-documentation)

---

## What the system does

The product is organised into six functional epics (full specs in [`_userstories/`](_userstories/README.md)):

| Epic | Theme | What it delivers |
|------|-------|------------------|
| **A** | Eligibility, classification & assessment type | Rich covenant targeting (Islamic banking, segment, industry, geography, deal/entity/facility/collateral type); `AUTO` (formula) vs `MANUAL` (human-verified) covenants; catalog-driven dropdowns; `cov_` table naming |
| **B** | Monitoring scheduling & visibility | Per-covenant frequency + start date (per-CP overridable); per-CP timeline (overdue / due-soon / upcoming / not-started / ended); a portfolio-wide time-ordered schedule |
| **C** | Data source & measure modeling | Read measures from both **columnar (wide)** tables and **key-value (EAV / row-per-measure)** tables |
| **D** | Formula & assessment semantics | Windowed aggregation with `FLAT` vs `PERIODIC` (period-end) sampling; fiscal-year-aligned quarter/year boundaries; trailing-period inclusion policy |
| **E** | ML-assisted configuration | ML-recommended thresholds during onboarding (preview + confirm; override only changed thresholds); threshold provenance (`MASTER`/`USER`/`ML`) + append-only recommendation audit |
| **F** | RM assessment, disposition & evidence | Unified By-CP / By-Covenant workspace; CP assessment grid (expression, actual, headroom, status); Waiver/Extension dispositions; evidence + compliance-certificate uploads; AI status suggestions; status provenance (`SYSTEM`/`USER`/`AI`) + append-only status audit |

---

## Domain concepts

- **Covenant (master)** — a reusable covenant template in the library (`cov_covenant_master`): a name,
  category (`FINANCIAL` / `NON_FINANCIAL`), eligibility dimensions, an assessment type (`AUTO`/`MANUAL`),
  a default monitoring frequency, and — for `AUTO` covenants — a **condition** expressed in the formula DSL.
- **Measure** — a named quantity a formula can reference (`DSCR`, `PAT`, `EBITDA`, `auditor`, …),
  mapped to a physical column or code in a data-source table (`cov_measure`).
- **Data source / table** — a registered source of customer time series — keyed per CP, or per obligor for group-level sources (`cov_data_source`,
  `cov_data_source_table`); either **columnar** (one column per measure) or **key-value** (one value column
  keyed by a code column).
- **Applied covenant** — a covenant attached to one CP (`cov_applied_covenant`), optionally with a per-CP
  threshold override, per-CP condition type (`PRECEDENT`/`SUBSEQUENT`), classification (`EXTERNAL`/`INTERNAL`),
  frequency and start date.
- **Assessment** — one immutable, append-only evaluation of an applied covenant (`cov_assessment`) with its
  result, computed value, evaluated expression and data snapshot.
- **Assessment result** — the machine verdict: `COMPLIANT`, `BREACHED`, `NA`, `ERROR`, `PENDING_REVIEW`.
  **A covenant condition that evaluates `true` ⇒ `COMPLIANT`.**
- **Disposition** — an RM's recorded outcome layered on a *Breached* row: `COMPLIED`, `BREACHED`,
  `WAIVER`, `EXTENSION` (`cov_covenant_disposition`). Does **not** mutate the assessment or dashboard counts.
- **Threshold source / Status source** — provenance tags: thresholds are `MASTER`/`USER`/`ML`; statuses are
  `SYSTEM`/`USER`/`AI`. Both are backed by append-only audit tables.
- **RM / WRM** — the Relationship Manager and Wholesale Relationship Manager on a CP; breach emails go to the
  RM (`emailTo`) cc the WRM (`emailCC`).

---

## Architecture & project layout

```
covenant-monitoring/
├── backend/            Spring Boot service (port 9310)
├── frontend/           React app (Vite dev server 9312, proxy → 9310)
├── _userstories/       Product specs: 6 epics, 20 stories (US-*.md) + glossary
├── docs/               user-stories.md · sprint-plan.md · quality-gates.md · runbook.md
├── .github/workflows/  CI: build → test → coverage → SAST → SCA → Sonar
├── build.bat / start.bat / stop.bat   Windows one-liners
└── README.md
```

### Backend package structure (layered / technical)

The backend is organised **by technical layer** under `com.crisil.covenant`, with the formula engine kept
cohesive as a self-contained module:

```
com.crisil.covenant
├── CovenantApplication         Spring Boot entry point
├── config                      @Configuration, properties, WebClient beans, OpenAPI, auditing, dev WireMock
├── controller                  @RestController REST endpoints
├── domain                      value objects / read models / projections (records)
├── dto                         request/response payloads (coverage-excluded)
├── entity                      JPA @Entity + @MappedSuperclass AuditableEntity
├── enums                       enum types (results, statuses, sources, categories, units …)
├── exception                   API exceptions + GlobalExceptionHandler + error envelope
├── repository                  Spring Data JPA repositories
├── service                     business services, Quartz jobs, external-client interfaces
│   └── impl                    WebClient implementations of the external-client interfaces
├── util                        stateless helpers (converters, SQL identifiers, calculators, specifications)
└── formula                     the measure-based DSL engine (lexer, parser, type checker, evaluator, renderer)
    └── web                     FormulaController + its request/response records
```

### Frontend structure

- **App shell** — `src/App.tsx` drives navigation with a **discriminated-union view state machine**
  (`{ kind: 'monitoring' | 'cp-assessment' | 'onboarding' | … }`), rendered inside a Mantine `AppShell`.
  `src/main.tsx` wires `MantineProvider`, TanStack `QueryClientProvider` (30s stale time, 1 retry) and
  Mantine `Notifications`.
- **Pages** — `src/pages/{covenant-master, measures, data-sources, formula, onboarding, monitoring, settings}`.
- **API layer** — `src/api/client.ts` (`apiFetch()` wrapper over native `fetch`, base path `/api/v1`,
  multipart upload support, typed `ApiClientError`) and `src/api/onboarding.ts` (grouped call modules:
  onboarding, monitoring, review, assessments, covenants, measures, data-sources, formulas, settings).

---

## Feature guide

### 1. Covenant library, eligibility & assessment type

**What it is.** The master library of reusable covenant templates (`cov_covenant_master`), managed on the
**Covenant Library** screen (list/search + create/edit form + detail with usage & audit).

**How it works.**
- Each covenant carries **eligibility dimensions** used to recommend it to a CP: Islamic banking (Yes/No),
  segment, industry, country of risk, country of incorporation, credit-proposal type, legal-entity type,
  facility type, collateral type. A multi-select left empty (or set to the synthetic **All**) means
  "wildcard — ignore this dimension when matching".
- Each covenant has an **assessment type**: `AUTO` (a formula the engine evaluates) or `MANUAL` (a free-text
  instruction a person verifies). **Formula validation runs only for `AUTO`.**
- Expression changes are recorded in an append-only expression change log; past assessments stay immutable.
- Every eligibility dropdown is sourced from its own external **master-data API** (one per category) — the
  frontend hardcodes no option lists and adds the **All** item client-side; dev/test run offline against
  bundled fixtures. (The former `cov_reference_data`-backed `/api/v1/reference-data` endpoint was retired —
  see US-DATA-06.)

**How to use.** Create a covenant → pick category and assessment type → choose eligibility → for `AUTO`,
author the condition in the formula builder (validated live) → save. `DELETE` is a soft-deactivate; the detail
screen shows how many CPs use it.

### 2. Measures & data sources

**What it is.** Measures are the named quantities formulas reference; data sources are the
customer tables — keyed per CP, or per obligor for group-level sources — they are read from.

**How it works.**
- A **data source** (`cov_data_source`) is a MSSQL or REST-backed source; under it live one or more **tables**
  (`cov_data_source_table`), each declaring a **key column**, an **as-of/date column**, an
  **entity-key level** (`CP_ID`, or `OBLIGOR_ID` for group-level sources), and a **layout**:
  - **Columnar (wide)** — each measure is its own physical column; a measure maps to a `source_column`.
  - **Key-value (EAV / row-per-measure)** — one value column holds many measures identified by a code column;
    a measure maps to a `source_code` matched against that code column (US-DS-10).
- Column/code discovery endpoints let the UI introspect a source and offer real column/code names when
  mapping measures, and a **test-connection** endpoint validates connectivity.

**How to use.** Register a data source → add a table (choose layout, key & as-of columns) → create measures
that map to columns (columnar) or codes (key-value). Measures then become available to the formula builder.
For a complete end-to-end example (external API → measures → covenants), see the
[frontend walkthrough](#walkthrough-wire-an-external-api-into-covenants-frontend).

### 3. The formula DSL

**What it is.** A small, safe expression language for covenant conditions and tracked values — evaluated
against windowed aggregations of time-series measure data. Implemented entirely in `com.crisil.covenant.formula`
(`Lexer` → `Parser` → AST `Node` → `FormulaTypeChecker` → `FormulaEvaluator`/`FormulaRenderer`).

**Grammar (by example).**

```
DSCR > 1.2                                        # bare measure vs literal threshold
AVG(PAT, '2y') > 1                                # aggregation over a 2-year window
LATEST(EBITDA, '6m') > 2                          # most-recent value in a 6-month window
PAT > 1 AND EBITDA > 2                            # boolean composition
(AVG(DSCR, '2y') >= 1.2) AND (SUM(PAT, '1y') > 100)
LATEST(auditor, '6m') == 'KPMG'                   # string comparison (qualitative)
```

- **Aggregation functions** — `AVG`, `SUM`, `MIN`, `MAX`, `LATEST` (a.k.a. `CURRENT`), `COUNT`.
- **Operators** — arithmetic `+ - * /`, comparison `> < >= <=`, equality `== !=`, logical `AND OR`, unary
  `NOT` / negation. Comparisons cannot be chained (write `a >= x AND a <= y`).
- **Literals** — numbers (incl. `%`), quoted strings, `TRUE`/`FALSE`, `DATE('yyyy-MM-dd')`; set membership
  via `IN`.
- **Guardrails** (Parser): max length 2000 chars, max nesting depth 32, max 64 terms.

**Windows & sampling (Epic D).**
- A window is written as `FUNC(measure, 'N<unit>')` where unit ∈ `d w m q y` (day/week/month/quarter/year).
- **`FLAT`** (default) — aggregate *every* reading whose as-of date falls in the rolling window.
- **`PERIODIC`** — first collapse each reporting period to its **period-end** figure (latest reading on/before
  the as-of date within that period), then aggregate the per-period figures. Only `m`/`q`/`y` are
  period-capable.
- **Trailing-period inclusion** — `PERIODIC` (org default), `PERIODIC_CURRENT` (force-include the in-progress
  period), `PERIODIC_COMPLETED` (completed periods only). Quarter/year boundaries follow the org's
  **fiscal-year start month** (Reporting Settings, US-AGG-11).

**Validation & thresholds.** `FormulaEngine.validate(...)` runs syntax → term collection → type checking
(e.g. `AVG` requires a numeric inner measure; a condition must be boolean) → a sample dry-run.
`FormulaEngine.thresholds(expression)` extracts the editable literal(s) in each comparison so the UI can offer
per-CP threshold editing without re-parsing by hand; `apply-thresholds` rebuilds and re-validates the
condition with edited values.

**How to use.** Author/validate on the **Formula Builder** screen or via `POST /api/v1/formulas/validate`;
`GET /api/v1/formulas/metadata` returns the function/window palette for the builder.

### 4. Recommendation engine

**What it is.** Given a CP, suggests which library covenants fit it (US-REC-04).

**How it works.** The engine loads the CP's eligibility attributes (from the Covenant Attributes API / CP
profile) and matches them against each covenant's eligibility dimensions. **All / empty on a dimension is a
wildcard** (that dimension neither includes nor excludes any CP). Existing covenants with no eligibility data
keep working (treated as all-wildcard).

**How to use.** `GET /api/v1/credit-proposals/{cpId}/recommendations`, surfaced as the recommendation step of
the onboarding wizard.

### 5. CP onboarding, applied covenants & ML thresholds

**What it is.** The **CP Onboarding wizard** takes a CP from lookup → recommendations → configure → apply.

**How it works.**
- **Apply** attaches selected covenants to the CP (`cov_applied_covenant`) in bulk, capturing per-CP
  frequency, start date, optional condition type (`PRECEDENT`/`SUBSEQUENT`) and classification
  (`EXTERNAL`/`INTERNAL`), and any per-CP threshold override.
- A CP-scoped **custom (MANUAL) covenant** can be created inline.
- **ML thresholds (Epic E)** — a button calls the (resilient, mockable) ML service, which returns recommended
  thresholds with confidence + rationale. The user previews and confirms; **only changed thresholds are
  overridden.** Each applied threshold is tagged with a **source** (`MASTER`/`USER`/`ML`), and **every ML
  recommendation is written to an append-only recommendation/threshold audit** — even when declined
  (US-ML-13).

### 6. Scheduling & monitoring visibility

**What it is.** Per-covenant cadence plus time-aware views of what needs attention.

**How it works.**
- Each covenant has a **default frequency**; a per-CP **Configure** step overrides frequency **and** start
  date (US-FREQ-07). Schedules are backed by **Quartz** (JDBC job store) via `ScheduleService`; each applied
  covenant gets its own cron trigger driving an `AssessmentJob`. `CronValidationController` validates a cron
  and previews the next fire times.
- **Timing status** is derived per applied covenant: *Not started · Up to date · Due soon · Overdue · Ended*,
  computed from the cron at/after `max(today, startDate)` (`MonitoringTimingCalculator`).
- **Per-CP timeline** (US-MON-08) shows each covenant's start, next due, last assessed and timing status.
- **Portfolio schedule** (US-MON-09) is one time-ordered agenda across all the user's CPs — overdue pinned
  first, then Due-soon / Upcoming buckets, with search and date-range filters.

**Schedule filter buckets — the logic in plain language.**
Every event on the schedule has a **next due date** (the first time the covenant should be assessed
*after* it was last assessed) and a **start date** (when monitoring begins). Using today's date, the
filter chips classify events into these buckets:

| Bucket | Plain-language meaning | Rule |
|---|---|---|
| **Overdue** | Its assessment date has already passed and it still hasn't been done. | `nextDueDate < today` |
| **Due soon** | It's due now or within the next few days. | `today ≤ nextDueDate ≤ today + dueSoonDays` |
| **Upcoming** | It has already started and is due later than "due soon", but still inside the look-ahead window. | `nextDueDate > today + dueSoonDays` **and** within `upcomingWindowDays` |

Overdue is always shown first (pinned) because it needs the most urgent action. Not-yet-started events
still appear in the agenda under their start date; an event that has ended, or one outside the
look-ahead window, is not shown at all.

**These windows are configurable** on the **Reporting settings** screen (`GET`/`PUT /settings/reporting`,
stored on the single `cov_reporting_setting` row), so filters re-classify immediately when changed:

| Setting | What it controls | Default |
|---|---|---|
| **Due soon (days)** — `dueSoonDays` | how many days ahead counts as "Due soon" | 7 |
| **Upcoming window (days)** — `upcomingWindowDays` | how far ahead the schedule looks for future events | 30 |

Each value must be between 1 and 365. The bucket labels reflect the live setting (e.g. "Due soon (7d)").
The backend `MonitoringTimingCalculator`/`MonitoringService` read these values when deriving each event's
timing, and the frontend `MonitoringSchedulePage` uses them to label and group the buckets.

### 7. The assessment engine

**What it is.** Evaluates one applied covenant and appends an immutable `cov_assessment` row.

**How it works** (`AssessmentService.runAssessment`):
1. Create a new append-only assessment (scheduled timestamp, trigger, actor).
2. **MANUAL** covenants → status `PENDING_MANUAL`, result `PENDING_REVIEW`; a person later records the outcome
   (`COMPLIANT`/`BREACHED`/`NA` + note) via *complete-manual* (US-ASM-05).
3. **AUTO** covenants → load the effective condition (per-CP override else master) → `FormulaEngine.compile`
   → build measure-aggregation requests → `MeasureAggregationResolver.resolve(cpId, asOf, requests,
   fiscalYearStartMonth, periodicIncludesCurrentPeriod)` issues the windowed SQL against each measure's data
   source and returns the resolved values.
4. **Result** — the condition is evaluated: `true ⇒ COMPLIANT`, `false ⇒ BREACHED`. Missing required data
   short-circuits to **`ERROR`** (a data-quality problem, surfaced separately — *not* a breach).
5. For audit, the assessment stores: `conditionExpression` (raw DSL), `evaluatedExpression` (DSL with values
   substituted), `computedValue` + `computedValueType` (the tracked value — the LHS of the first comparison),
   and `dataSnapshotJson` (all resolved values). A downstream **workflow** is kicked off with the outcome.

### 8. Daily breach run & RM/WRM notifications

**What it is.** A daily (or on-demand, date-selectable) sweep that assesses every ACTIVE covenant due on the
run date and emails owners (US-MON-14).

**How it works.** `DailyBreachRunJob` (registered at startup by `DailyRunInitializer`, default cron
`0 0 6 * * ?`, configurable via `covenant.notifications.cron`) assesses all due covenants under a correlating
`runId`, then groups the touched CPs by **(RM email, WRM email) pair** so each distinct pair receives exactly
one consolidated email (RM `emailTo`, WRM `emailCC`) listing all their CPs. Every attempt is written to the
append-only `cov_notification_audit` (request, response, HTTP status, outcome, duration, actor, `runId`). The
whole run is resilient — a failing send never aborts the sweep. Trigger on demand with
`POST /api/v1/monitoring/notification-runs`.

### 9. RM assessment workspace, grid & disposition

**What it is.** The RM action surface reached from the breach email (Epic F).

**How it works.**
- **Monitoring workspace** (US-MON-15) — one landing screen with a **By CP / By Covenant** toggle merging the
  US-MON-14 per-CP summary and the US-MON-09 agenda; selecting a CP opens its assessment grid.
- **CP assessment grid** (US-MON-16) — one row per applied covenant showing **Covenant · Expression (effective
  per-CP condition) · Actual computed value · Headroom · Status · Start · Next due · Last assessed ·
  Attachments**.
  - **Derived status** — from the stored result: `BREACHED ⇒ Breached`; `COMPLIANT`/`NA ⇒ Complied`
    (engine semantics unchanged).
  - **Headroom** — signed distance from threshold in the compliant direction (upper-bound: `threshold − actual`;
    lower-bound: `actual − threshold`; positive = cushion, negative = breach size). Shown only for a single
    numeric comparison; otherwise "—". Non-financial rows hide Actual + Headroom.
  - **Disposition** — editable **only when the latest result is `BREACHED`**; the RM records **Waiver** or
    **Extension** (`cov_covenant_disposition`). **Extension requires a mandatory extension date**; Waiver needs
    none. Complied/NA rows are locked. Dispositions never mutate `cov_assessment` or the dashboard counts.

### 10. Evidence attachments & compliance certificate

**What it is.** Document backing for decisions (US-MON-17).

**How it works.** Files are uploaded **through the backend** (consistent with all external calls being
server-side), which forwards each to the external **file-upload service** and persists the returned `fileId` +
filename. Evidence attaches at **CP+covenant** level (`cov_covenant_attachment`); a CP-level, multi-file
**compliance certificate** panel stores at CP level (`cov_compliance_certificate`). Uploads are non-mandatory,
listed with uploader + timestamp, and removable. The upload path is wrapped in the standard
timeout/retry/circuit-breaker; a failed upload shows a clear error and never corrupts existing data.

### 11. AI status suggestions

**What it is.** Optional decision support (US-MON-18).

**How it works.** An **AI Suggestion** action calls the backend, which calls the external AI service with the
CP's covenant data + uploaded `fileId`s; it returns a **recommended status per covenant** (with optional
rationale/confidence) in a modal aligned to each row. The RM chooses which to apply/override or dismisses with
no change; applying updates the disposition and audits the change with source **AI** (an AI *Extension* still
needs an extension date). The call is resilient — on failure the modal shows a graceful "AI unavailable"
message with no crash and no data change.

### 12. Status provenance & audit

**What it is.** Proof of who set each status, when, and why (US-MON-19) — mirroring ML threshold audit.

**How it works.** Every disposition carries a **status source** (`SYSTEM` derived / `USER` person-set / `AI`
accepted suggestion). Every change appends a `cov_covenant_status_audit` row (old/new status, old/new
extension date, source, rationale, actor, timestamp) — never updated or deleted. `cov_assessment` stays
immutable. The trail is queryable per CP and per applied covenant.

---

## Walkthrough: wire an external API into covenants (frontend)

This is the end-to-end path a configurer follows **in the UI** to turn a brand-new external API into
live covenants — from *"here is an API that returns financial data points per obligor"* to
*"these covenants are assessed COMPLIANT/BREACHED on schedule."* It uses the consolidated,
as-of-aware REST contract introduced by the data-source overhaul (US-DS-11/12/13).

**Scenario.** A lender monitors facilities for the corporate group **ACME Group** (obligor
`OBL-ACME`). Group financials come from an external **Obligor Financials API** that publishes a
handful of data points — **DSCR, PAT, EBITDA, current ratio** — for each **financial (as-of)
date**. Several credit proposals (`CP-5001`, `CP-5002`, …) belong to `OBL-ACME` and must all be
assessed against the same group figures.

### 0. The contract your source must serve

A REST source exposes **one** as-of-aware endpoint (default path `/api/data/values`). The platform
routes to the source's own **Base URL** (per-source routing) and issues a single `POST` whose body
tells the source what window to return:

```jsonc
// POST {baseUrl}/api/data/values
{
  "cpId": "OBL-ACME",                 // the lookup key — obligor id for an OBLIGOR_ID table (see step 2)
  "from": "2024-03-31",               // omit / null  ⇒ return only the latest point ≤ "to"
  "to":   "2026-03-31",               // present      ⇒ return every point in [from, to]
  "keys": ["DSCR", "PAT", "EBITDA", "currentRatio"]
}
```

The source replies with a `DataSeriesResponse` — a list of dated points, plus an optional
`sourceChangedAt` telling the platform when its data last changed:

```json
{
  "cpId": "OBL-ACME",
  "points": [
    { "asOfDate": "2024-03-31", "values": { "DSCR": 1.42, "PAT": 120.5, "EBITDA": 180.0, "currentRatio": 1.35 } },
    { "asOfDate": "2025-03-31", "values": { "DSCR": 1.31, "PAT": 138.2, "EBITDA": 205.0, "currentRatio": 1.28 } },
    { "asOfDate": "2026-03-31", "values": { "DSCR": 1.18, "PAT": 96.4,  "EBITDA": 150.5, "currentRatio": 1.12 } }
  ],
  "missingKeys": [],
  "sourceChangedAt": "2026-04-05T00:00:00Z"
}
```

The keys inside each point's `values` map are the physical field names you map measures to in step 3.
One endpoint serves both point-in-time and windowed reads, so a multi-year window (e.g.
`AVG(EBITDA, '2y')`) is satisfied from a single ingest rather than one snapshot per assessment.

### 1. Register the data source · *Data Sources → New*

| Field | Value | Maps to |
|---|---|---|
| Name | `Obligor Financials API` | `name` |
| Type | **REST** | `type` |
| Description | *(optional)* | `description` |
| Base URL | `https://financials.internal.example.com` *(dev: `http://localhost:9311`)* | `connectionConfig.baseUrl` |

Save → `POST /api/v1/data-sources`. The values path defaults to `/api/data/values`; set a
`valuesPath` in the connection config only if your endpoint lives elsewhere.

### 2. Describe the data (add a table) · *open the source → Tables → New*

A **table** tells the platform the *shape* of what the source returns for this feed.

| Field | Value | Why |
|---|---|---|
| Name | `Group Financials` | label |
| Table name | `obligor_financials` | logical id (for REST it's just a name) |
| Entity key column | `obligor_id` | the grouping key the source is keyed on |
| As-of column | `as_of_date` | the time axis for windowed aggregation |
| **Entity key level** | **OBLIGOR_ID** | the API is keyed at **group/obligor** grain, not per-CP |
| Mapping shape (layout) | **COLUMNAR** | each data point is its own key in `values` |
| Code / Value column | *(leave blank)* | KEY_VALUE layout only |

Save → `POST /api/v1/data-sources/{id}/tables`.

> **Why OBLIGOR_ID matters.** With `entityKeyLevel = OBLIGOR_ID`, when a covenant on `CP-5001` is
> assessed the engine first resolves that CP's obligor (`OBL-ACME`) from `cov_obligor_cp_map` and
> sends **`"cpId": "OBL-ACME"`** to the API — so one group pull fans out to every CP under the
> obligor. Results still attach back to the CP being assessed; only the *lookup key* changes. Use the
> default **CP_ID** when the source is keyed per credit proposal.
>
> Choose **KEY_VALUE** instead of COLUMNAR when the source returns rows of `(code, value)` (one row
> per measure) rather than one field per measure; you'd then set the Code and Value columns and map
> each measure by *source code* in step 3.

### 3. Create the measures · *Measures → New (one per data point)*

A measure is the formula-facing name (`code`) bound to a physical field (`sourceColumn`) on the table
above. Code and column need not match — that's how `CURRENT_RATIO` reads the `currentRatio` field.

| Code | Name | Data source | Table | Source column | Default aggregation | Data type | Unit |
|---|---|---|---|---|---|---|---|
| `DSCR` | Debt Service Coverage Ratio | Obligor Financials API | Group Financials | `DSCR` | `LATEST` | `NUMBER` | x |
| `PAT` | Profit After Tax | Obligor Financials API | Group Financials | `PAT` | `LATEST` | `NUMBER` | Cr |
| `EBITDA` | EBITDA | Obligor Financials API | Group Financials | `EBITDA` | `LATEST` | `NUMBER` | Cr |
| `CURRENT_RATIO` | Current Ratio | Obligor Financials API | Group Financials | `currentRatio` | `LATEST` | `NUMBER` | x |

Save each → `POST /api/v1/measures`. They immediately appear in the formula builder.

### 4. Author the covenants · *Covenant Library → New*

Create `AUTO` covenants whose **condition** is a formula over the measures (validated live in the
builder). Leave the eligibility dimensions as **All** (wildcard) to keep them broadly recommendable.

| Covenant | Category | Assessment type | Condition (formula DSL) | Default frequency |
|---|---|---|---|---|
| Minimum DSCR | FINANCIAL | AUTO | `DSCR >= 1.25` | QUARTERLY |
| Current-ratio floor | FINANCIAL | AUTO | `CURRENT_RATIO >= 1.2` | QUARTERLY |
| 2-year average EBITDA floor | FINANCIAL | AUTO | `AVG(EBITDA, '2y') > 150` | YEARLY |
| Sustained profitability | FINANCIAL | AUTO | `MIN(PAT, '2y') > 0 AND LATEST(PAT, '1y') > 100` | YEARLY |

Save each → `POST /api/v1/covenants`. (A covenant condition that evaluates **true ⇒ COMPLIANT**.)

### 5. Apply to a CP · *CP Onboarding wizard*

Look up `CP-5001`. **The lookup records the CP → obligor mapping** (`CP-5001 → OBL-ACME`) that step 2
relies on. Accept the recommended covenants (or add them), configure per-CP frequency / start date /
any threshold override, and **Apply** → `POST /api/v1/credit-proposals/{cpId}/applied-covenants`.

### 6. Assess · *on schedule, or `POST /api/v1/assessments/run`*

On the first run the freshness gate finds no staged copy (**reason `missing`**), issues **one**
as-of-aware call for the widest window any covenant needs (here `[2024-03-31, 2026-03-31]` for the
`'2y'` windows, as of 2026-03-31), stages the points, and evaluates every covenant. With the sample
data above:

| Covenant | Evaluated | Result |
|---|---|---|
| `DSCR >= 1.25` | latest DSCR `1.18` | **BREACHED** |
| `CURRENT_RATIO >= 1.2` | latest `1.12` | **BREACHED** |
| `AVG(EBITDA, '2y') > 150` | window avg ≈ `178` | COMPLIANT |
| `MIN(PAT,'2y') > 0 AND LATEST(PAT,'1y') > 100` | min `96.4` > 0, **but** latest `96.4` < 100 | **BREACHED** |

Breaches surface in the RM workspace / CP assessment grid (expression, actual, headroom, status),
where an RM can record a Waiver/Extension.

### What the overhaul does for you here

- **One call, both shapes.** Point-in-time (`from` omitted) and windowed (`[from, to]`) reads hit the
  *same* endpoint; a `'2y'` covenant no longer needs one snapshot per assessment.
- **Per-source routing.** Each source is called at its own **Base URL**, so different feeds can live
  on different hosts.
- **Incremental + reach-back.** A same-day re-run finds the copy fresh and **skips the source**; only
  genuinely newer dates (`max+1 … asOf`) are fetched. If a new covenant later needs an *earlier*
  `from` than what's staged, the platform does one full `[from, asOf]` reach-back refetch.
- **Change-aware.** When the source advances `sourceChangedAt`, the next assessment re-ingests
  (**reason `source-changed`**) even inside the TTL. (MSSQL sources don't report change yet, so they
  re-pull on the 24-hour TTL.)
- **No false "fresh".** If a control row says *fresh* but staging is somehow empty, the gate overrides
  and re-ingests (**reason `staging-empty`**) rather than raising *Missing values*.

### Try it against the bundled dev stub

Under the `dev` profile the app auto-starts WireMock on **:9311** and already serves an **obligor**
example at `POST /api/data/values` — obligor `OBL-ADANI`, keys `group_dscr` / `group_net_leverage` /
`group_interest_cover`, across FY quarter-ends 2022–2026 (`wiremock/mappings/demo-obligor-values-adani.json`).
Set the source's **Base URL** to `http://localhost:9311`, add an **OBLIGOR_ID / COLUMNAR** table, map
measures to those three keys, and assess a CP whose profile resolves to `OBL-ADANI`.

---

## REST API reference

Base path `/api/v1` (Swagger UI at `/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs`). Every error uses the
shared `ErrorResponse` envelope (code, message, optional field details, timestamp).

**Covenant library** — `CovenantMasterController` `/covenants`
| Method & path | Purpose |
|---|---|
| `POST /covenants` | Create a library covenant |
| `PUT /covenants/{id}` | Update (expression changes tracked) |
| `DELETE /covenants/{id}` | Soft-deactivate |
| `GET /covenants/{id}` · `GET /covenants` | Get one · search/filter (paginated) |
| `GET /covenants/{id}/audit` · `GET /covenants/{id}/usage` | Expression change log · CP usage counts |

**Measures** — `MeasureController` `/measures`: `POST` · `PUT /{id}` · `DELETE /{id}` · `GET /{id}` · `GET /`.

**Data sources** — `DataSourceController` `/data-sources`: CRUD + `GET /{id}/columns` (discover columns) +
`POST /{id}/test` (test connection). **Tables** — `DataSourceTableController`
`/data-sources/{dataSourceId}/tables`: CRUD + `GET /{tableId}/columns` + `GET /{tableId}/code-values`
(distinct codes for a key-value table).

**Formulas** — `FormulaController` `/formulas`: `POST /validate` · `GET /metadata` · `POST /thresholds` ·
`POST /apply-thresholds`.

**Reference data** — *retired.* Eligibility dropdown catalogs are now fetched by the frontend directly from
external master-data services (bundled fixtures in dev/test); there is no backend reference-data endpoint.

**Recommendation & onboarding** — `RecommendationController` `/credit-proposals`:
`GET /{cpId}` (CP details, cached) · `GET /{cpId}/recommendations` · `POST /{cpId}/custom-covenants`.

**Applied covenants** — `AppliedCovenantController`:
`POST /credit-proposals/{cpId}/applied-covenants` (bulk apply) · `GET /credit-proposals/{cpId}/applied-covenants`
· `PATCH /applied-covenants/{id}` (frequency/cron/status) · `GET /applied-covenants/{id}/audit`.

**ML thresholds** — `MlThresholdController`
`POST /credit-proposals/{cpId}/ml-threshold-recommendations`. (Dev-only stand-in: `MockMlThresholdController`
`POST /internal/mock-ml/threshold-recommendations`, `dev` profile.)

**Assessments** — `AssessmentController` `/assessments`:
`POST /run` · `GET /` (by CP, newest first) · `GET /{id}` · `POST /{id}/complete-manual`.

**Monitoring** — `MonitoringController` `/monitoring`:
`GET /summary` (per-CP counts) · `GET /cps/{cpId}/covenants` · `GET /schedule` (agenda) ·
`GET /applied-covenants/{appliedId}/history`. Plus `NotificationRunController`
`POST /monitoring/notification-runs` (trigger daily breach run) and `CronValidationController`
`POST /schedules/validate-cron`.

**RM review (Epic F)** — `CovenantAssessmentController` `/monitoring`:
| Method & path | Purpose |
|---|---|
| `GET /cp/{cpId}/review` | Assessment grid (expression, actual, headroom, status, attachments) |
| `PUT /cp/{cpId}/covenants/{appliedId}/status` | Record Waiver/Extension disposition |
| `GET /cp/{cpId}/status-audit` · `GET /covenants/{appliedId}/status-audit` | Append-only status trail |
| `POST`/`DELETE /cp/{cpId}/covenants/{appliedId}/attachments[/{id}]` | Evidence upload/remove |
| `GET`/`POST`/`DELETE /cp/{cpId}/compliance-certificate[/{id}]` | Compliance certificate |
| `POST /cp/{cpId}/ai-suggestions` | Per-covenant AI status suggestions |

**Reporting settings** — `ReportingSettingsController` `/settings/reporting`: `GET` · `PUT`
(fiscal-year start month + in-progress-period flag for PERIODIC aggregation, plus the monitoring-schedule
filter windows: `dueSoonDays`, `upcomingWindowDays`).

---

## External integrations, resilience & WireMock

All outbound calls are server-side, via WebFlux `WebClient` client interfaces (in `service`) with
implementations in `service.impl`, configured in `config/ClientsProperties` + `config/WebClientsConfig`:

| Client | External system / purpose |
|---|---|
| `CreditProposalClient` | CP profile lookup (segment, product, exposure) — cached |
| `CovenantAttributesClient` | CP eligibility attributes for recommendation |
| `DataApiClient` | Time-series measure values as of a date |
| `DataCatalogClient` | Discover source tables/columns for measure mapping |
| `MlThresholdRecommender` | ML threshold recommendations (US-ML-12) |
| `NotificationClient` | RM/WRM breach emails (US-MON-14) |
| `FileUploadClient` | Evidence + compliance-certificate uploads (US-MON-17) |
| `AiStatusRecommender` | Per-covenant AI status suggestions (US-MON-18) |
| `WorkflowClient` | Kick off downstream workflow on assessment |

Base URLs are configured under `covenant.clients.*` in `application.yml` (`credit-proposal`, `attributes`,
`data-api`, `workflow`, `ml`, `notification`, `file-upload`, `ai-suggestion`). Each call is wrapped with
**resilience4j** circuit breaker + retry + time limiter (per-instance timeouts, e.g. ML 3s, notifications /
file-upload / AI 10s). Failures **degrade gracefully** — ML/AI return empty suggestions; notification/upload
return a failure result — they never throw into the request path.

**Dev/demo (`dev` profile).** `WireMockDevConfig` auto-starts WireMock on **:9311**; stub mappings live in
`backend/src/main/resources/wiremock/mappings/` (`data-values.json`, `data-catalog.json`, `notifications.json`,
`file-upload.json`, `ai-suggestions.json`, `workflows.json`, plus demo CP/attribute stubs). Edit a stub's
`values` map to drive `COMPLIANT` / `BREACHED` / `ERROR` outcomes during a demo. **Quartz** uses a JDBC job
store (MSSQL delegate, `QRTZ_*` tables).

---

## Data model

- **Tables use a `cov_` prefix** (project convention — see [`CLAUDE.md`](CLAUDE.md)): `cov_covenant_master`,
  `cov_measure`, `cov_data_source`, `cov_data_source_table`, `cov_applied_covenant`, `cov_assessment`,
  `cov_covenant_recommendation`, `cov_monitoring_schedule`, `cov_applied_covenant_audit`,
  `cov_expression_change_log`, `cov_credit_proposal_cache`, `cov_reporting_setting`,
  `cov_threshold_audit`, `cov_notification_audit`, `cov_covenant_disposition`, `cov_covenant_status_audit`,
  `cov_covenant_attachment`, `cov_compliance_certificate`.
- **Not** prefixed (framework/infra): the 11 Quartz `QRTZ_*` tables, Flyway's `flyway_schema_history`, and the
  dev-only demo seed `cp_metrics_demo` (simulates an external customer source).
- **Migrations** — Flyway. Prod: `backend/src/main/resources/db/migration` (`V1`, `V3`, …). Dev-only demo seed:
  `backend/src/main/resources/db/demo` (`V10`, `V20`, …, `dev` profile only). DDL is MSSQL-compatible and also
  runs on H2 in `MODE=MSSQLServer` (`IDENTITY`, `DATETIME2`, `BIT`, named PK/UK/CK/FK). Hibernate runs
  `ddl-auto: validate`. A consolidated reference script lives at `backend/src/main/resources/db/all_tables.sql`.
  Never edit a released migration — add a new versioned file.

---

## Running locally

> Nothing is on `PATH` in this workspace — use absolute tool paths (JDK 17, Maven, Node) and set `JAVA_HOME`
> before Maven, per [`CLAUDE.md`](CLAUDE.md).

### Windows one-liner scripts (repo root)

```
build.bat          full build: mvn verify + npm test/lint/audit/sast/build
start.bat          dev: backend (profile=dev, WireMock :9311) + Vite dev (:9312); auto-builds JAR if missing
start.bat prod     prod: backend jar + vite preview (:9313) — run build.bat first
stop.bat           stop everything (frees ports 9310/9311/9312/9313)
```

### Backend (manual)

```
cd backend
mvn -P fast spring-boot:run -Dspring-boot.run.profiles=dev   # dev; skips OWASP + SpotBugs for fast feedback
mvn -P fast test                                              # fast unit-test loop (skips ITs/SpotBugs/OWASP)
mvn -P no-owasp verify                                        # full gate without the slow NVD download
mvn verify                                                    # full build + all quality gates
```

Endpoints: API `http://localhost:9310/api/v1/...` · Swagger `…/swagger-ui.html` · H2 console `…/h2-console`
· health `…/actuator/health`.

### Frontend (manual)

```
cd frontend
npm ci
npm run dev            # Vite dev server on :9312, proxying /api → :9310
npm run type-check     # tsc --noEmit
npm run test           # Vitest
npm run test:coverage  # Vitest + coverage gate
npm run lint           # ESLint (--max-warnings=0)
npm run sast           # security ESLint config
npm run audit:ci       # SCA on production deps
npm run build          # tsc --noEmit && vite build
npm run test:e2e       # Playwright (needs build + preview running)
```

---

## Quality gates & testing

| Gate | Backend | Frontend |
|---|---|---|
| Coverage | JaCoCo **line ≥95% / branch ≥90%** (bundle); DTOs/config/entry-point excluded | Vitest **statements ≥95 / branches ≥85 / functions ≥75 / lines ≥95**; `hooks/**`, `types/**` excluded |
| SAST | SpotBugs `Effort=Max, Threshold=Low` + FindSecBugs — **0 findings** | ESLint security config (`sast`) — 0 errors |
| SCA | OWASP Dependency-Check — fail on **CVSS ≥ 7** | `audit-ci` on production deps |
| Lint / build | — | ESLint `--max-warnings=0`; clean `vite build` |
| E2E | — | Playwright covers P0 flows |
| Sonar | Quality gate must pass | Quality gate must pass |

**Current status.** Backend builds green on Spring Boot 3.5.14 — `mvn -P no-owasp verify` passes with **660
unit tests + 29 integration tests**, JaCoCo coverage met, SpotBugs 0 findings. Logging is CRLF-safe
(parameterized, ≤2-arg constant templates with inline `\n`/`\r` sanitisation) to satisfy FindSecBugs.

Test suites mirror the layered packages (`service`, `controller`, `entity`, `util`, …), with `*IT` integration
tests run by Failsafe and unit tests by Surefire. The `formula` engine has a dedicated language/type-checker/
thresholds/window test set; the WebClient integrations are covered with WireMock (2xx → success; timeout/5xx →
graceful fallback).

---

## Further documentation

- **[`_userstories/`](_userstories/README.md)** — the authoritative product specs: 6 epics, 20 stories with
  acceptance criteria, and a full domain glossary. Read the relevant story before changing a covenant /
  recommendation / assessment behaviour.
- **[`CLAUDE.md`](CLAUDE.md)** — project conventions (table naming, Flyway rules, quality gates, build/test
  commands).
- **[`docs/`](docs/)** — `user-stories.md`, `sprint-plan.md`, `quality-gates.md`, `runbook.md`.
