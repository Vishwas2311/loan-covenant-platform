# Covenant Monitoring — Complete Functional & Technical Guide

> **Audience:** Business Analysts, developers, RMs/credit officers, and anyone preparing for a client
> discussion. This guide documents **every feature that has been built** and **every scenario** you are
> likely to be asked about — including the "unhappy paths" (missing data, unreachable sources, blank
> users, etc.). Where a rule is subtle, a worked **Example** is given.
>
> **How to read this:** Each part starts with a plain-English **What it is / Why it matters** for the BA,
> then drills into **How it works** (exact classes, enums, fields) for the developer, then lists the
> **Rules & scenarios** (including edge cases) in a table.
>
> **Source of truth:** This guide was written from the actual source under
> `D:\workspace_sv\covenant-monitoring`. Package root `com.crisil.covenant`. Where the code contains a
> deliberate quirk (e.g. the triple-`e` `manualAssigneee`), it is called out explicitly.

---

## Table of contents

1. [What the application does (the 60-second pitch)](#1-what-the-application-does)
2. [Architecture & technology at a glance](#2-architecture--technology-at-a-glance)
3. [Domain glossary (read this first)](#3-domain-glossary)
4. [The end-to-end lifecycle](#4-the-end-to-end-lifecycle)
5. [Part A — Covenant authoring (the Covenant Library)](#part-a--covenant-authoring)
6. [Part B — Measures & data sources](#part-b--measures--data-sources)
7. [Part C — The covenant formula language (DSL)](#part-c--the-covenant-formula-language-dsl)
8. [Part D — Recommendations & ML thresholds](#part-d--recommendations--ml-thresholds)
9. [Part E — CP onboarding & applied covenants](#part-e--cp-onboarding--applied-covenants)
10. [Part F — Scheduling (Quartz)](#part-f--scheduling-quartz)
11. [Part G — The assessment engine (and every missing-data scenario)](#part-g--the-assessment-engine)
12. [Part H — Workflow-case integration](#part-h--workflow-case-integration)
13. [Part I — The daily breach run & notifications](#part-i--the-daily-breach-run--notifications)
14. [Part J — Monitoring surfaces (By-CP, By-Covenant, per-CP)](#part-j--monitoring-surfaces)
15. [Part K — Monitoring timing calculator](#part-k--monitoring-timing-calculator)
16. [Part L — Dispositions (Waiver / Extension), attachments, AI suggestions](#part-l--dispositions-attachments-ai-suggestions)
17. [Part M — Reporting settings](#part-m--reporting-settings)
18. [Part N — RM/WRM portfolio scoping (fail-closed)](#part-n--rmwrm-portfolio-scoping)
19. [Part O — Support / Ops toolkit](#part-o--support--ops-toolkit)
20. [Part P — Frontend screens (every page)](#part-p--frontend-screens)
21. [Part Q — Cross-cutting rules & the master edge-case catalogue](#part-q--cross-cutting-rules--edge-cases)
22. [Appendix 1 — Enum reference](#appendix-1--enum-reference)
23. [Appendix 2 — Database tables](#appendix-2--database-tables)
24. [Appendix 3 — User-story map](#appendix-3--user-story-map)
25. [Appendix 4 — Build, test & quality gates](#appendix-4--build-test--quality-gates)

---

## 1. What the application does

**In one sentence:** Covenant Monitoring lets a bank author lending *covenants* (the promises a borrower
makes in a loan agreement), attach the right ones to each *credit proposal* (CP), automatically check
whether the borrower is keeping those promises, and route breaches to the right relationship manager for
disposition.

A **covenant** is a contractual condition — e.g. *"Debt-to-EBITDA must stay below 3.5"* or *"Submit
audited financials every year"*. The tool covers the whole life of a covenant:

1. **Author** a reusable covenant in the library (its meaning, how often it is checked, and — for the
   automated ones — a machine-checkable *formula*).
2. **Recommend & apply** the eligible covenants to a specific CP when the CP is onboarded.
3. **Schedule** each applied covenant to be re-checked on its own cadence (daily/weekly/…).
4. **Assess** compliance automatically by pulling the borrower's financial/behavioural data, aggregating
   it over a time window, and evaluating the formula → **Compliant / Breached / Error**. Manual covenants
   are checked by a human.
5. **Monitor** the whole portfolio through three lenses (By-CP, By-Covenant agenda, per-CP drill-in).
6. **Dispose** of breaches an RM has decided to tolerate — a **Waiver** (forgive this breach) or an
   **Extension** (give the borrower more time). Evidence can be attached.
7. **Notify** RMs every morning of what breached, with an audit trail; and open a **workflow case** per CP
   so downstream teams can action it.
8. **Support/Ops** tools let a support engineer diagnose why a covenant did not resolve, check data-source
   health, re-ingest/re-assess, purge a CP's data, and read an audit trail — all behind a support token.

**Who uses it**
- **Relationship Manager (RM) / credit officer** — sees only their own portfolio; reviews breaches;
  records waivers/extensions; uploads compliance certificates.
- **Covenant/product team** — authors the reusable covenant library, measures, data sources and formulas.
- **Support/Ops engineer** — diagnoses and remediates data/ingestion issues.

---

## 2. Architecture & technology at a glance

| Layer | Technology | Notes |
|-------|-----------|-------|
| Backend | **Spring Boot 3.3, Java 17** | REST API under `/api/v1/**`; `@Service`/`@Transactional` service layer. |
| Persistence | **JPA/Hibernate**, `ddl-auto: validate` | Every table is created by **Flyway**, never by Hibernate. |
| Database | **SQL Server** (prod); **H2 in `MODE=MSSQLServer`** (dev/test) | DDL must run on both. |
| Scheduling | **Quartz** with a JDBC job store (`QRTZ_*` tables) | Durable jobs survive restarts. |
| Frontend | **React 18, Mantine v8, Vite, TanStack Query** | **No react-router** — navigation is a `useState` state-machine in `App.tsx`. |
| External integrations | Credit-proposal service, ML/AI recommenders, notification service, file-upload service, workflow engine | All called via `WebClient`; all resilient (never crash the app). |

**Table-naming rule (important):** *every* application/audit table is prefixed **`cov_`**
(`cov_covenant_master`, `cov_assessment`, …). The only unprefixed tables are the framework-managed Quartz
`QRTZ_*` tables, Flyway's `flyway_schema_history`, and the dev-only demo seed `cp_metrics_demo` (which
*simulates an external customer data source*, so it is deliberately not part of "our" schema).

**Flyway layout:** production migrations in `db/migration` (`V1`, `V3`, …); dev-only demo seed in
`db/demo` (`V10`, `V20`, …) loaded **only under the `dev` profile**.

**The durable staging mirror — the heart of assessment data:** external data is never evaluated live.
Before evaluation, the tool copies the rows it needs into a durable table **`cov_staging_value`** (an
Entity-Attribute-Value table). Assessment then reads *only* from staging. This makes an assessment
reproducible, audit-friendly, and decoupled from source availability at read time.

---

## 3. Domain glossary

| Term | Meaning |
|------|---------|
| **CP (Credit Proposal)** | A specific lending deal/borrower relationship, identified by a `cpId`. Everything is ultimately scoped to a CP. |
| **Obligor** | The legal entity behind one or more CPs. Some data sources are keyed at obligor grain, not CP grain (see `entityKeyLevel`). |
| **Covenant master** | The **reusable template** of a covenant in the library (`cov_covenant_master`). Defines meaning, category, default frequency, eligibility, and (for AUTO) the formula. |
| **Applied covenant** | A covenant master **attached to a specific CP** (`cov_applied_covenant`), with its own frequency, start/end dates, status, and optional per-CP formula override. This is the *unit that gets scheduled and assessed.* |
| **Measure** | A named, reusable *quantity* (e.g. `EBITDA`, `TOTAL_DEBT`) that maps to a column or a key in a data-source table, with a default aggregation and data type. Formulas are written in terms of measures. |
| **Data source** | An external system (REST API or MSSQL database) that supplies the raw values behind measures. |
| **Data-source table** | A specific table/endpoint inside a data source, with a *layout* (COLUMNAR or KEY_VALUE) and an *entity-key level* (CP or obligor). |
| **Assessment** | One execution of one applied covenant at a point in time (`cov_assessment`) — append-only. Produces a **result** and a **data snapshot**. |
| **Disposition** | An RM's decision on a breach: **Waiver** or **Extension** (`cov_covenant_disposition`). |
| **RM / WRM** | Relationship Manager and (W)holesale RM — the two owners of a CP's portfolio. Drives who can see the CP and who gets notified. |
| **Staging value** | A durable copy of one measurable data point in `cov_staging_value`, keyed by table + entity + as-of date + item. |

---

## 4. The end-to-end lifecycle

Here is the whole journey of a single covenant, tying the parts together. Each step links to its detailed
part below.

```
                 AUTHOR                      APPLY                        RUN
   ┌──────────────────────┐   ┌───────────────────────────┐   ┌───────────────────────────┐
   │ Covenant Library      │   │ CP Onboarding wizard      │   │ Quartz fires on cadence   │
   │  - master + formula   │──▶│  - recommend eligible     │──▶│  OR daily breach sweep    │
   │  - measures           │   │  - RM tunes threshold/    │   │  OR "Run now" (manual)    │
   │  - data sources       │   │    frequency/start        │   │                           │
   │  (Parts A,B,C)         │   │  - apply → applied cov.   │   │  → AssessmentService      │
   └──────────────────────┘   │  (Parts D,E)              │   │    (Part G)               │
                              └───────────────────────────┘   └─────────────┬─────────────┘
                                                                             │
                              ┌───────────────────────────┐                 ▼
                              │ Monitoring surfaces        │   ┌───────────────────────────┐
   ┌──────────────────────┐   │  By-CP / By-Covenant /     │   │ At start of run:          │
   │ RM reviews a breach   │◀──│  per-CP drill-in          │◀──│  open ONE workflow case   │
   │  - Waiver / Extension │   │  (Part J, timing Part K)  │   │  per CP (Part H)          │
   │  - attach evidence    │   └───────────────────────────┘   │ At end of daily sweep:    │
   │  - AI suggestion      │                                    │  email RM/WRM (Part I)    │
   │  (Part L)             │                                    └───────────────────────────┘
   └──────────────────────┘
```

**Narrative example (keep this in mind as you read):**
> *CP-2001 (borrower "Vedanta") is onboarded. The wizard recommends the "Debt-to-EBITDA ≤ 3.5" covenant
> (AUTO, quarterly) and the "Submit audited financials yearly" covenant (MANUAL, yearly). The RM applies
> both. Quartz schedules the AUTO one to run every quarter. On the next quarter-end the assessment pulls
> the borrower's debt and EBITDA from the "Demo MSSQL Financials" source, aggregates them, computes
> `4.1 ≤ 3.5` → **false** → **BREACHED**. The morning sweep emails the RM. The RM opens CP-2001, sees the
> breach, and records a 60-day **Extension** with a covenant-waiver letter attached. The By-CP dashboard
> immediately shows "1 breached (1 handled)" and the overdue flag clears until the extension date.*

---

## Part A — Covenant authoring

*(User stories: US-CM-01, US-CM-02, US-CM-03, US-CM-22, US-CM-23, US-DATA-06)*

### What it is / why it matters (BA)
The **Covenant Library** is the master catalogue of reusable covenant templates. Authoring a covenant
once here means it can be recommended and applied to many CPs consistently. A covenant is either
**AUTO** (the system checks it with a formula) or **MANUAL** (a human checks it and records the verdict).

### How it works (developer)

**Entity `CovenantMaster` (`cov_covenant_master`)** — the key fields:

| Field | Meaning |
|-------|---------|
| `name`, `description` | Human identity of the covenant. |
| `category` | `FINANCIAL` or `NON_FINANCIAL` — **descriptive only**; it does *not* change how evaluation runs, but it *does* drive UI behaviour (non-financial covenants hide computed value & headroom — see Part L). |
| `assessmentType` | `AUTO` or `MANUAL` — **this is the switch that drives evaluation** (Part G). |
| `conditionExpression` | For AUTO: the formula DSL string. For MANUAL: free-text instructions to the human assessor. |
| `defaultFrequency` | `DAILY / WEEKLY / MONTHLY / QUARTERLY / YEARLY / CUSTOM`. |
| Eligibility attributes | 8 dimensions used by the recommendation matcher (see Part D) + an Islamic-banking flag. |

**Eligibility & the "All" wildcard (US-CM-02):** eligibility is expressed across 8 dimensions (business
segment, sector, etc.). A dimension can list specific allowed values **or** be left as **"All"**. The
convention is:
- **"All" / an empty multiselect means "matches everything"** — the dimension is simply *omitted* from
  matching. In the UI a `MultiSelectAll` control defaults to `['ALL']`.

**Assessment type (US-CM-03):**
- **AUTO** → must have a boolean formula; validated at author time.
- **MANUAL** → `conditionExpression` holds instructions, not a formula; never auto-evaluated.

**CP-scoped custom covenants (US-CM-22):** an RM can add a one-off covenant that exists only for a single
CP. These carry `is_custom = true`, an `owner_cp_id`, and are **always MANUAL / NON_FINANCIAL** (there is
no shared formula for a bespoke covenant).

**The formula builder (US-CM-23):** authors build/validate AUTO formulas in a live editor
(`FormulaBuilderPage`) that calls `formulasApi.validate({requireBoolean:true})` on every change and can
dry-run against sample values.

**Impact guard (important safety rule):** if you **edit the formula** of a covenant master that is already
applied to **≥1 active CP**, the UI forces an explicit *"confirm formula change"* acknowledgement before
saving — because the change will affect live monitoring. Every expression change is recorded in
`cov_expression_change_log` and surfaced on the covenant detail page.

### Rules & scenarios

| Scenario | Behaviour |
|----------|-----------|
| Author leaves all eligibility dimensions as "All" | Covenant is eligible for **every** CP. |
| Author picks specific values for one dimension | Covenant matches only CPs whose attribute is in that list (see Part D matching). |
| Islamic-banking covenant | Matched by **exact** flag equality — **no wildcard** applies to the Islamic flag. |
| Edit a formula on a widely-applied covenant | Confirmation gate + change-log entry; already-running assessments keep their own snapshot of the old formula. |
| Custom (CP-scoped) covenant | Forced MANUAL / NON_FINANCIAL, owned by one CP, not in the shared library. |

---

## Part B — Measures & data sources

*(User stories: US-DS-10, US-DS-11, US-DS-12, US-DS-13, US-DATA-06)*

### What it is / why it matters (BA)
A **measure** is a named quantity a formula can refer to (e.g. `EBITDA`). Measures decouple the formula
language from where the data physically lives. A **data source** is the external system (a REST API or a
SQL database) that provides those numbers; a **data-source table** is one specific feed inside it.

### How it works (developer)

**`Measure`** key fields:
- `code` (used in formulas), `name`, `description`.
- `dataSourceTable` — which feed supplies it.
- **`sourceColumn`** (used when the table layout is **COLUMNAR**) **or** **`sourceCode`** (used when the
  layout is **KEY_VALUE**).
- `defaultAggregation` — `LATEST / CURRENT / AVG / SUM / MIN / MAX / COUNT`.
- `dataType` — `NUMBER / STRING / DATE / BOOLEAN`.

**`DataSource`** — `type` is **REST** or **MSSQL**.
- **REST**: `baseUrl`, `valuesPath`.
- **MSSQL**: `jdbcUrl`, `username`, `password` (secret), `schema`, `tableName`, `cpIdColumn`, `asOfColumn`.

**`DataSourceTable`** — the two things that matter most:
- **`layout`**: **`COLUMNAR`** (one row per entity/as-of date, measures are columns) **or** **`KEY_VALUE`**
  (each measure is a `(itemCode, value)` pair). *There is no "ROW" layout* — only these two.
- **`entityKeyLevel`**: **`CP_ID`** (keyed per CP) or **`OBLIGOR_ID`** (keyed per obligor). This is
  critical for the missing-data behaviour: an obligor-keyed table needs an obligor mapping for the CP.

**Data-source onboarding journey (US-DS-11):** register a source → discover its columns (a "Test
connection" round-trip reports reachability + column count) → define its tables → map measures. The
`ColumnDiscoveryService` powers the column pickers in the UI.

**Multi-year REST series fetch (US-DS-12):** REST sources can return a *series* of values across periods,
which the aggregation windows then sample.

**Freshness-gated ingestion (US-DS-13):** the tool does not blindly re-pull data. `IngestionOrchestrationService.decide(state, requiredAsOf, now)` returns a `Decision(ingest, reason)` where
`reason ∈ {missing, stale, source-changed, ttl, fresh}`. If a prior copy is still fresh, ingestion is
skipped; if staging is empty an override forces a pull. A nightly **pre-stage sweep** (default 05:30, ahead
of the 06:00 breach run) warms staging so the morning assessment is fast.

### Rules & scenarios

| Scenario | Behaviour |
|----------|-----------|
| Measure on a COLUMNAR table | Reads `sourceColumn` from a row keyed by entity + as-of. |
| Measure on a KEY_VALUE table | Reads the row whose `itemCode == sourceCode`. |
| Table keyed at OBLIGOR_ID, CP has an obligor mapping | Data resolved at obligor grain. |
| Table keyed at OBLIGOR_ID, CP has **no** obligor mapping | That table is **skipped** during ingestion → its measures resolve as **missing** → assessment ends in **ERROR** (see Part G, Gate 3). |
| Source still fresh per TTL | Ingestion skipped, prior staged copy reused. |
| Staging empty | Ingestion forced regardless of freshness. |
| DDL for a new table | Must be MSSQL- *and* H2(`MODE=MSSQLServer`)-compatible; use `IDENTITY`, `DATETIME2`, `BIT`, named constraints. |

---

## Part C — The covenant formula language (DSL)

*(User stories: US-AGG-11; engine under `backend/.../formula`)*

### What it is / why it matters (BA)
AUTO covenants are written in a small, purpose-built formula language. A formula is a **boolean
condition** ("is the borrower compliant?") built from **measures**, **numbers/dates**, **operators**, and
**windowed aggregation functions** (e.g. *"average EBITDA over the last 4 quarters"*).

### How it works (developer)

Pipeline: **Lexer → Parser → type check → bind measures → evaluate** (`FormulaEngine`).

**Limits (guardrails):** `MAX_LENGTH = 2000` chars, `MAX_TERMS = 64`, `MAX_DEPTH = 32`.

**Operator precedence** (highest binds last): `OR` > `AND` > `NOT` > comparison (non-associative) >
additive `+ -` > multiplicative `* /` > unary > primary.
- **Comparisons are non-associative** — `a < b < c` is **rejected** (no chained comparisons).
- Equality/inequality must be written `==` / `!=`.
- `%` means "÷100" (a literal `20%` becomes `0.20`).
- Dates are written `DATE('YYYY-MM-DD')`.

**Aggregation functions (`FunctionKind`):** `AVG, SUM, MIN, MAX, LATEST, CURRENT, COUNT`.

**Windows:** a number + unit — `D` (day), `W` (week), `M` (month), `Q` (quarter), `Y` (year). `M/Q/Y` are
**period-capable** (calendar-aligned periods). `SamplingMode` is **FLAT** or **PERIODIC**, and periodic
sampling honours a `PeriodInclusion` policy (whether the current, in-progress period counts — controlled by
the reporting setting `periodicIncludesCurrentPeriod`, see Part M).

**How values are resolved:** the aggregation resolver reads `cov_staging_value`, choosing the right typed
column (`num_value / str_value / date_value / bool_value`) per the measure's `dataType`.

### Worked examples

| Formula | Meaning |
|---------|---------|
| `TOTAL_DEBT / EBITDA <= 3.5` | Leverage cap. Both are `LATEST` by default. |
| `AVG(EBITDA, 4Q) >= 1000000` | Average quarterly EBITDA over the last 4 quarters ≥ 1,000,000. |
| `CURRENT_RATIO >= 1.2 AND QUICK_RATIO >= 0.8` | Two liquidity tests, both must hold. |
| `LATEST(DSCR, 1Y) >= 1.25` | Latest debt-service-coverage over a 1-year window. |
| `NET_MARGIN >= 15%` | `15%` is parsed as `0.15`. |

### Rules & scenarios (and the *evaluation* edge cases)

| Scenario | Behaviour |
|----------|-----------|
| Formula longer than 2000 chars / >64 terms / >32 depth | Rejected at compile with a `FormulaException`. |
| Chained comparison `a < b < c` | Rejected by the parser. |
| Unknown measure code | `bind` throws `"Measure '<code>' is not registered"` → assessment ERROR (Part G, Gate 1). |
| **Division by zero** at evaluation | `FormulaEvaluator` throws `FormulaException("Division by zero")` → assessment ERROR "Evaluation error: Division by zero". |
| Top-level result not boolean | `FormulaEngine.evaluate` throws `"Formula did not evaluate to true/false"` → ERROR (normally unreachable for well-typed saved covenants because the type checker catches it at author time). |

---

## Part D — Recommendations & ML thresholds

*(User stories: US-REC-04, US-ML-12, US-ML-13, US-ML-20)*

### What it is / why it matters (BA)
When a CP is onboarded, the tool proposes which library covenants apply, and — for financial ones — can
even suggest the numeric **threshold** to use, learned from similar deals. The RM stays in control: every
suggestion can be accepted, edited, or rejected.

### How it works (developer)

**Rule-based matching (US-REC-04):** a covenant master matches a CP when **every** eligibility dimension
matches (logical AND across dimensions). A dimension set to **"ALL"** or **empty is skipped** (wildcard).
The **Islamic-banking** flag is an **exact match with no wildcard**. Recommendation rows carry a
`RecommendationStatus` of `SUGGESTED / ACCEPTED / REJECTED / MANUAL_ADD`.

**ML thresholds (US-ML-12, US-ML-20):** `MlThresholdService` calls an external model with **only the CP
payload** (privacy-minimal), is **resilient** (an empty-list fallback if the model is unavailable — never
blocks onboarding), and **audits every RECOMMENDED value**.

**Three distinct tagging systems — do not confuse them (US-ML-13):**

| System | Values | Where it lives |
|--------|--------|----------------|
| `ThresholdSource` | `MASTER / USER / ML` | Provenance of a *threshold number* on an applied covenant. |
| Grid `source` string | `RECOMMENDED / ML / MANUAL / CUSTOM` | The onboarding grid's per-row origin label. |
| `CovenantStatusSource` | `SYSTEM / USER / AI` | Provenance of a *disposition* decision (Part L). |

### Rules & scenarios

| Scenario | Behaviour |
|----------|-----------|
| CP matches covenant on all dimensions | Covenant is **SUGGESTED** in the onboarding grid. |
| Covenant has "ALL" on a dimension | That dimension never blocks a match. |
| Islamic mismatch | Covenant not recommended even if all other dimensions match. |
| ML model down / errors | `MlThresholdService` returns an empty list; onboarding proceeds with MASTER defaults. |
| RM overrides an ML threshold | Threshold stored with `ThresholdSource.USER`; audited. |

---

## Part E — CP onboarding & applied covenants

*(User stories: US-FREQ-07, US-CM-22, US-ML-20)*

### What it is / why it matters (BA)
Onboarding is the two-step wizard where an RM turns *recommendations* into *applied covenants* on a CP —
tuning the threshold, frequency, start date, and classification for each.

### How it works (developer)

**Wizard (`CpOnboardingWizard`):** `Lookup → Review & Configure → Completed`. It shows a merged grid of
recommended / ML / manual / custom covenants. Per row the RM sets: threshold (`ThresholdEditor`,
`thresholdSource MASTER/ML/USER`), frequency (`FrequencyPicker`), condition type
(`PRECEDENT/SUBSEQUENT`), classification (`EXTERNAL/INTERNAL`), start date, then applies.

**Entity `AppliedCovenant` (`cov_applied_covenant`)** key fields:
- `cpId`, `covenantMaster`.
- `frequency` + `cronExpression`.
- `startDate` (**must be ≥ today** at apply time), `endDate`.
- `status`: `ACTIVE / SUSPENDED / EXPIRED`.
- `conditionType`: `PRECEDENT` (must be satisfied before drawdown) / `SUBSEQUENT` (ongoing).
- `classification`: `EXTERNAL` / `INTERNAL`.
- `thresholdSource`, and an optional **per-CP `conditionExpression` override** (falls back to the
  master's formula when blank).

**Default crons per frequency (`Frequency` enum):**

| Frequency | Default cron | Reads as |
|-----------|--------------|----------|
| DAILY | `0 0 2 * * ?` | 02:00 every day |
| WEEKLY | `0 0 2 ? * MON` | 02:00 every Monday |
| MONTHLY | `0 0 2 1 * ?` | 02:00 on the 1st |
| QUARTERLY | `0 0 2 1 1,4,7,10 ?` | 02:00 on Jan/Apr/Jul/Oct 1st |
| YEARLY | `0 0 2 1 1 ?` | 02:00 on Jan 1st |
| CUSTOM | `null` | Requires an explicit cron |

**Two documented quirks (not bugs, but know them):**
1. `AppliedCovenant.snapshot_json` is a **dead placeholder** (always `"{}"`). The *real* per-run snapshot
   is `Assessment.data_snapshot_json` (Part G).
2. `endDate` has **no writer** in the app today; **`EXPIRED` is a manual-only status** with **no schedule
   teardown** (the Quartz trigger is left in place — see Part F).

### Rules & scenarios

| Scenario | Behaviour |
|----------|-----------|
| RM sets a start date in the past | Rejected — start date must be ≥ today. |
| RM adds a custom covenant | Forced MANUAL/NON_FINANCIAL, CP-scoped. |
| Covenant already applied to the CP | Row shows an "Already applied" badge in the wizard. |
| Frequency = CUSTOM with no cron | No effective schedule (timing calculator returns null cron). |

---

## Part F — Scheduling (Quartz)

*(User stories: US-FREQ-07, US-MON-14; `ScheduleService`, `AssessmentJob`, `DailyBreachRunJob`,
`IngestionPreStageJob`)*

### What it is / why it matters (BA)
Every applied covenant is put on an automatic timer so it is re-checked on its cadence, without anyone
clicking a button. Two portfolio-wide timers also run: the **daily breach sweep** and the **nightly
data pre-stage**.

### How it works (developer)

`ScheduleService` (constants: `JOB_GROUP="covenant-assessments"`, `TRIGGER_GROUP="covenant-triggers"`,
`DAILY_RUN_KEY="daily-breach-run"`, `PRE_STAGE_RUN_KEY="ingestion-pre-stage-run"`).

**Per-covenant granularity — `createSchedule(applied)`:**
- One **job + one trigger per applied covenant**, keyed `"assessment-" + applied.getId()`.
- The applied-id is stored on the job data map **as a String** (`APPLIED_ID_KEY="appliedCovenantId"`) —
  required because the JDBC job store runs `useProperties=true`.
- Trigger uses the applied covenant's cron with
  **`withMisfireHandlingInstructionFireAndProceed()`** — a missed fire runs once then resumes (no storm).
- An invalid cron → `ApiException(400, "INVALID_CRON")`; a scheduler failure → `500 SCHEDULE_FAILED`.
- Persists a `MonitoringSchedule` row (`quartzJobKey`, cron, `status=SCHEDULED`, `nextRunAt`).

**`AssessmentJob.executeInternal`** (`REQUIRES_NEW` tx): reads the applied-id string, and calls the
**3-arg** `assessmentService.runAssessment(appliedId, SCHEDULED, null)`. Because it is 3-arg, each isolated
Quartz fire gets its **own** workflow-case context (its own case — see Part H). It catches all
`RuntimeException` (never rethrows) and updates `lastRunAt`/`nextRunAt` on the schedule row afterward.

**Two singleton portfolio jobs** (restart-safe/idempotent via `addJob(…, true)` + reschedule-if-exists):
- **`DailyBreachRunJob`** — the morning sweep, default cron **`0 0 6 * * ?`** (06:00), configurable via
  `covenant.notifications.cron`. Registered at startup by `DailyRunInitializer`.
- **`IngestionPreStageJob`** — warms `cov_staging_value`, default ~05:30 (ahead of the breach run).
- Both registrations are wrapped independently so one failing never blocks the other or startup.

**Status → schedule wiring** (lives in `AppliedCovenantService`, not `ScheduleService`):

| Status change | Effect |
|---------------|--------|
| `SUSPENDED` | Quartz job **paused**; schedule row → `PAUSED`. |
| `ACTIVE` | Quartz job **resumed**; schedule row → `SCHEDULED`. |
| `EXPIRED` | **No scheduler action** — the trigger stays; expiry is enforced only through due-date timing (`endDate`) and the ACTIVE-only daily sweep filter. |

### Rules & scenarios

| Scenario | Behaviour |
|----------|-----------|
| App restarts | Durable jobs and the two singletons survive; singletons are re-registered idempotently. |
| A scheduled fire is missed (downtime) | Fire-and-proceed: fires once on recovery, then normal schedule. |
| Covenant suspended mid-life | Its per-covenant job is paused **and** the daily sweep skips it (non-ACTIVE). |
| Covenant expired | Left on the Quartz store but never "due"; also skipped by the ACTIVE-only sweep. |

---

## Part G — The assessment engine

*(User stories: US-036 append-only, US-ASM-05 manual, US-ASM-21 comparisons; `AssessmentService`)*

**This is the most-asked-about part in client discussions — especially "what happens when data is
missing?" The answer is the gate ladder below.**

### What it is / why it matters (BA)
An **assessment** is one check of one applied covenant. AUTO covenants are checked by the engine; MANUAL
ones wait for a human. Every check is a **brand-new, append-only row** — history is never overwritten.

### How it works (developer)

`AssessmentService.runAssessment` has two overloads:
- **3-arg** `(appliedId, trigger, user)` → opens a **fresh** workflow-case context, then delegates.
- **4-arg** `(appliedId, trigger, user, ctx)` → the real engine, sharing a caller-supplied context (the
  daily sweep uses this so all covenants of a CP share one workflow case).

**Lifecycle (exact order):**
1. Load the `AppliedCovenant` (or `ResourceNotFoundException`).
2. Build a **new** `Assessment` (`scheduledAt = now`, `triggeredBy`, `triggeredByUser`).
3. **Open the CP's workflow case at start** — *before any evaluation*:
   ```java
   CaseOutcome caseOutcome = workflowCaseService.ensureCase(applied.getCpId(), ctx);
   assessment.setWorkflowInstanceId(caseOutcome.caseId());
   assessment.setWorkflowError(caseOutcome.error());
   ```
   So the case id (or its error) is stamped on **every** terminal row that follows.
4. Branch on `master.getAssessmentType()`:

**MANUAL branch (US-ASM-05):** set `status = PENDING_MANUAL`, `result = PENDING_REVIEW`, copy the
instructions into `conditionExpression`/`evaluatedExpression`, **save and return**. No data is pulled, no
formula runs. It waits for `completeManual(...)`.

**AUTO branch:** `status = IN_PROGRESS`, snapshot the effective condition (per-CP override or master),
save an in-progress row, then run **the gate ladder:**

| Gate | Check | On failure |
|------|-------|-----------|
| **G1 — Compile & bind** | `formulaEngine.compile(...)` then `measureBinder.bind(...)`. | `result = ERROR`, message **`"Formula error: …"`**. |
| **G2 — Data source reachable / ingest & aggregate** | JIT ingest into staging, then `aggregationResolver.resolve(...)`. Any `RuntimeException`. | `result = ERROR`, message **`"DATA_SOURCE_FAILURE: …"`**. |
| **G3 — Missing values** | Any requested measure whose value is null / not present is collected. `data_snapshot_json` is captured (present values only) **before** this check. | `result = ERROR`, message **`"Missing values for measures: [..]"`**. |
| **G4 — Evaluate** | Render resolved expression, then `formulaEngine.evaluate(...)`. `FormulaException` (e.g. divide-by-zero). | `result = ERROR`, message **`"Evaluation error: …"`**. |
| **Pass** | `satisfied ? COMPLIANT : BREACHED`. | `status = COMPLETED`, `executedAt = now`. |

**The single most important scenario — missing data:** the **AUTO path never produces NA**. Missing rows,
null values, an obligor-unmapped table, an unreachable source, or a divide-by-zero **all become ERROR**
(with the specific message above). **NA is manual-only** (see below).

**Manual completion — `completeManual(assessmentId, result, note, completedByUser)`:**
- Only allowed when `status == PENDING_MANUAL` (else `409 ASSESSMENT_NOT_PENDING_MANUAL`).
- `result` must be **`COMPLIANT | BREACHED | NA`** (else `400 INVALID_MANUAL_RESULT`).
- **A note is mandatory** (else `400 MANUAL_NOTE_REQUIRED`).
- Sets result/note/`executedAt`, `status = COMPLETED`; may overwrite `triggeredByUser` with the completer.

**Comparisons decomposition (US-ASM-21):** each comparison in the formula produces an
`AssessmentThreshold` child row (ordinal, label, operator, threshold display value, actual, value type) —
this is what powers the per-threshold breakdown in the UI. No comparisons ⇒ no rows.

**Data snapshot:** `data_snapshot_json` holds a `LinkedHashMap` of the **present, resolved** measure
values keyed by `measure@AGG@window`. For MANUAL rows it stays the default `"{}"`.

**Immutability guard:** once a row is `COMPLETED`, changing `result / dataSnapshotJson /
evaluatedExpression / executedAt` throws `409 ASSESSMENT_IMMUTABLE`.

**Log-injection safety:** ids and error messages are CR/LF-sanitised before logging (`sanitise(...)`).

### Rules & scenarios (the master missing-data table)

| Situation | AUTO result | Message |
|-----------|-------------|---------|
| Formula won't compile / unknown measure | **ERROR** | `Formula error: …` |
| Data source unreachable / ingest fails | **ERROR** | `DATA_SOURCE_FAILURE: …` |
| No rows in window / null value | **ERROR** | `Missing values for measures: […]` |
| Obligor-keyed table but CP has no obligor mapping | **ERROR** | `Missing values for measures: […]` (table was skipped at ingest) |
| Divide by zero | **ERROR** | `Evaluation error: Division by zero` |
| Formula not boolean at top level | **ERROR** | `Evaluation error: Formula did not evaluate to true/false` |
| Everything present, condition true | **COMPLIANT** | — |
| Everything present, condition false | **BREACHED** | — |
| Covenant is MANUAL | **PENDING_MANUAL / PENDING_REVIEW** until a human completes it | — |
| Human completes MANUAL as value-only | **NA** (requires a note) | — |
| Workflow engine unreachable at start | Assessment **still runs to completion**; `workflow_error` set, `workflow_instance_id` null | — |

---

## Part H — Workflow-case integration

*(`WorkflowCaseService`, `WorkflowCaseContext`, `WorkflowClient` / `WebClientWorkflowClient`)*

### What it is / why it matters (BA)
When assessment of a CP begins, the tool opens **one workflow "case" per CP** in an external workflow
engine so downstream teams have a task to action. Opening the case never blocks or fails the assessment.

### How it works (developer)

**One case per CP *per run* (dedup):** `WorkflowCaseContext` holds a `Map<cpId, CaseOutcome>` and
`computeIfAbsent(...)` so the case is created **exactly once per CP per run** — a CP with N covenants opens
one case; all N assessment rows share its `caseId`. **Both success and failure are memoised**, so a CP
whose case failed is not retried for every covenant in the same run.

**What a "run" is:** the daily sweep shares **one** context across all CPs (one case per CP for the whole
batch). An isolated Quartz fire or a manual "Run now" each get their **own** context (their own case).

**`WorkflowCaseService.createCaseForCp(cpId)`:**
1. `resolveRm(cpId)` → the RM userId to assign.
2. `workflowClient.startCase(new WorkflowCaseRequest("Covenant_Monitoring", new Variables(rm)))` →
   `POST /api/runtime/cases`.
3. If the returned `caseId` is null/blank → `CaseOutcome.error("Workflow returned empty body")`.
4. Otherwise fire the 3 downstream placeholder steps and return `CaseOutcome.ok(caseId)`.
5. Any `RuntimeException` → `CaseOutcome.error(sanitised message)`.

**RM assignee resolution order (`resolveRm`) — the `manualAssigneee` value:**
1. **Cache first** — `cov_credit_proposal_cache.rm_user_id` (if non-blank).
2. **Else live lookup** — `creditProposalClient.lookup(cpId).rm().userId()` (best-effort; a thrown error is
   caught and ignored).
3. **Else blank / start anyway** — the case is opened with a null assignee. *Never block the assessment.*

**Deliberate quirks / placeholders (call these out to clients):**
- **`manualAssigneee` is spelled with a triple-`e` on purpose** — it matches the workflow engine's
  published contract verbatim.
- The **3 downstream calls** (`submitCaseStep1/2/3`) are **placeholders** — log-only, marked
  `TODO(workflow): contract not yet defined`; no real HTTP call yet. A step failure only logs a warning
  and can never lose the `caseId`.
- The case id is stored in the pre-existing column **`workflow_instance_id`** (the name predates the
  "case" terminology; no schema change was needed).

### Rules & scenarios

| Scenario | Behaviour |
|----------|-----------|
| CP with 5 covenants in the daily sweep | **1** `POST /api/runtime/cases`; all 5 rows share the caseId. |
| Same CP assessed via 2 separate manual "Run now" clicks | 2 cases (each click is its own run). |
| RM not in cache but CP-service returns it | Case assignee = looked-up RM userId. |
| RM unknown everywhere | Case still created with a blank assignee. |
| Workflow engine down | `CaseOutcome.error`; assessment completes normally with `workflow_error` set. |
| Engine returns empty body | `CaseOutcome.error("Workflow returned empty body")`. |

---

## Part I — The daily breach run & notifications

*(User story US-MON-14; `BreachNotificationService`, `WebClientNotificationClient`)*

### What it is / why it matters (BA)
Every morning the tool sweeps the whole portfolio, assesses everything that is due, and emails each RM
(cc'ing the WRM) a per-CP summary of what breached — with a full audit trail of every email.

### How it works (developer)

`BreachNotificationService.run(asOf, triggeredBy)` — deliberately **not** class-level `@Transactional`
(each unit is its own transaction so one failure can't abort the batch). Constants:
`ACTIVE_PAGE_SIZE = 500`, `BODY_MAX = 4000`, `ERROR_MAX = 512`.

**Phases:**
1. **Actor & context:** `actor = triggeredBy or current user or "system"`; open **one shared**
   `WorkflowCaseContext` for the whole sweep.
2. **Find due & assess (paginated):** page through `findByStatus(ACTIVE, 500-per-page)`. For each covenant
   compute timing; **due** = `OVERDUE` *or* `nextDueDate == runDate`. Assess due ones via the **4-arg**
   `runAssessment(..., ctx)`. A per-covenant `RuntimeException` is logged and skipped (batch isolation).
   One grouped query fetches latest-executed-at per page (no N+1).
3. **Per-CP summary + RM/WRM grouping:** for each touched CP get `summaryFor(cpId)` and
   `creditProposalClient.lookup(cpId)` (best-effort). Group CPs by the **`(RM email, WRM email)` pair** —
   two CPs merge into one email **only if they share both**.
4. **Send + audit:** one email per pair → `NotificationRequest("COV","Cov_Template", rmEmail(To),
   wrmEmail(Cc), rows, "HIGH")`. Each email carries a `cpMonitoring` array — one row per CP with keys
   `CP_ID, Applied, Compliant, Breached, Error, Pending`. Every call writes a `NotificationAudit` row
   (SUCCESS/FAILED, request/response bodies truncated, duration, actor). A CP whose RM has **no email** is
   **assessed but not notified** and produces **one aggregate FAILED audit** row.
5. Returns `RunResult(runId, covenantsAssessed, cpCount, sent, failed)`.

**Resilience:** `WebClientNotificationClient` wraps the send in **TimeLimiter → CircuitBreaker → Retry**
(resilience4j) and **never throws** — a failed send becomes a failed `NotificationResult`.

**Triggers:** the Quartz `DailyBreachRunJob` at 06:00 (as `"system"`), or the UI
`POST /api/v1/monitoring/notification-runs?date=…` (attributed to the acting user).

**Idempotency:** re-running the same day is safe — already-assessed covenants are no longer "due", so they
are skipped.

**No local email templates:** there is no `EmailService` in the codebase — templating is entirely
delegated to the external notification service via `templateCode="Cov_Template"`.

### Rules & scenarios

| Scenario | Behaviour |
|----------|-----------|
| CP with due breaches, RM has email | Assessed; RM emailed (WRM cc'd); SUCCESS audit. |
| CP's RM has no email | Assessed; **not** emailed; one aggregate FAILED audit row. |
| Two CPs share the same RM **and** WRM | Merged into a single email with two rows. |
| Notification service down | Send returns failed result (no crash); FAILED audit row. |
| Re-run the sweep same day | Idempotent — nothing re-assessed. |
| One covenant throws mid-sweep | Logged and skipped; the batch continues. |

---

## Part J — Monitoring surfaces

*(User stories US-MON-08, US-MON-09, US-MON-15, US-MON-16, US-MON-20, US-MON-21, US-MON-22;
`MonitoringService`, `ReviewService`)*

### What it is / why it matters (BA)
Three complementary lenses on the same data, all **scoped to the signed-in RM's portfolio**:
- **By-CP** — a portfolio scorecard: per CP, how many covenants are compliant / breached / error / pending
  / **handled**.
- **By-Covenant** — a time-ordered agenda: what is overdue, due this week, upcoming.
- **Per-CP drill-in / review grid** — the working screen where an RM reviews and disposes covenants.

### How it works (developer)

All three go through `MonitoringService.ownedCpIds(user)`, which is **fail-closed**: a blank/absent user
sees **nothing** (see Part N).

**Surface A — By-CP summary (`summary(user)` / `summaryFor(cpId)`):** builds a 6-slot tally per CP via
`accumulate(...)` reading each covenant's latest assessment:
- `COMPLIANT → compliant++`
- `BREACHED → breached++`; **and** if the breach is *handled* (an active disposition pinned to the latest
  assessment), also `dispositioned++` (the breach count is **not** decremented — you see "3 breached
  (2 handled)").
- `ERROR → error++`, `PENDING_REVIEW / no assessment yet → pendingReview++`, `NA →` counted only in the
  applied total (value-only, deliberately no compliance bucket).
- Record: `CpMonitoringSummary(cpId, applied, compliant, breached, error, pendingReview, dispositioned)`.

**Surface B — By-Covenant schedule (`schedule(from, to, status, cpId, q, user)`):** builds
`MonitoringEvent`s. Window defaults to `today … today + upcomingWindowDays`. For each ACTIVE covenant it:
1. computes timing, 2. **applies the extension defer (`withExtension`) BEFORE the bucket/window filters**
(so an extended covenant is re-bucketed and no longer shows as overdue), 3. filters by status/window (OVERDUE
is always included), 4. text-searches on covenant name or cpId, 5. stamps `dispositionStatus` +
`extensionDate` **only when handled**.

**Surface C — per-CP list (`covenantsForCp`)** and the **review grid (`ReviewService.reviewRows`)** — the
same deferral and "active disposition" rule applied consistently, so all three surfaces always agree.

**Review grid specifics (US-MON-16):** per covenant it shows expression, **actual value** (hidden for
NON_FINANCIAL — AC10), **headroom** (`HeadroomCalculator`, FINANCIAL only), status with provenance chip,
timing, and attachments. A row is **dispositionable** only when the latest result is BREACHED.

### Rules & scenarios

| Scenario | Behaviour |
|----------|-----------|
| CP has 3 breaches, 2 waived/extended | By-CP shows "3 breached (2 handled)". |
| An extended covenant | Drops out of "overdue"; re-bucketed by its effective (extension) date across all surfaces. |
| NON_FINANCIAL covenant | No computed value / no headroom shown. |
| RM drills into a CP not in their portfolio | Empty list (fail-closed). |
| "Hide handled" toggle (By-Covenant) | Filters out events with an active disposition (US-MON-21 AC4). |

---

## Part K — Monitoring timing calculator

*(User story US-MON-08/09; `MonitoringTimingCalculator`)*

### What it is / why it matters (BA)
This is the single place that decides, for each covenant, **when it is next due** and **what state it is
in** — the colour-coded badge you see everywhere.

### How it works (developer)

**`TimingStatus`:** `NOT_STARTED, UP_TO_DATE, DUE_SOON, OVERDUE, ENDED`. **`DUE_SOON_DAYS = 7`** default
(overridable by the reporting setting `dueSoonDays`).

**`compute(...)` order:** ended? → not started? → no next-due? (`UP_TO_DATE`) → next-due before today?
(`OVERDUE`, with `overdueByDays`) → within the due-soon horizon? (`DUE_SOON`) → else `UP_TO_DATE`.
Next-due is derived from the effective cron via Quartz's `CronExpression.getNextValidTimeAfter(...)`,
based on last-assessed (or start date, or epoch).

**`withExtension(base, extensionDate, today, dueSoonDays)` — the extension-defer rule:**
- Returns `base` unchanged if there is no extension date, or the covenant is `ENDED`/`NOT_STARTED`.
- **Frequency-preempts rule:** the extension date becomes the new next-due **unless** the frequency's next
  occurrence is *both* in the **future** *and* **earlier** than the extension date — in which case that
  earlier frequency date wins. A *past/overdue* frequency date never wins (that is exactly what the
  extension is deferring).
- The effective date is then re-classified into OVERDUE / DUE_SOON / UP_TO_DATE just like `compute`.

### Worked example (memorise this for the client)

> Today = 15-Jun. A quarterly covenant was due 01-Jun → **OVERDUE by 14 days**. The RM grants an
> **Extension to 05-Jul**. `withExtension`: the frequency's *next future* occurrence is 01-Sep (later than
> 05-Jul), so it does **not** preempt → effective date = **05-Jul** → **UP_TO_DATE** (or DUE_SOON once
> inside 7 days of 05-Jul). The overdue flag is cleared.
>
> Contrast: if the extension were to **10-Sep**, the frequency's 01-Sep occurrence is future *and* earlier
> → **01-Sep preempts** → effective next-due = 01-Sep.

### Rules & scenarios

| Scenario | Status |
|----------|--------|
| Start date in the future | `NOT_STARTED` |
| End date in the past | `ENDED` (dropped from agendas) |
| Next-due earlier than today | `OVERDUE` (with day count) |
| Next-due within `dueSoonDays` | `DUE_SOON` |
| Extension in the future, no earlier future frequency date | Deferred to extension date; overdue cleared |
| WAIVER (no date) | Timing **unchanged** (a waiver forgives, it does not defer) |

---

## Part L — Dispositions, attachments, AI suggestions

*(User stories US-MON-16, US-MON-17, US-MON-18, US-MON-19, US-MON-20, US-MON-21, US-MON-22)*

### What it is / why it matters (BA)
A **disposition** is the RM's formal decision about a breach:
- **Waiver** — forgive this breach (no date). Timing is unchanged; the breach is marked "handled".
- **Extension** — give the borrower more time (requires a date). Timing is deferred to that date.

Evidence (letters, certificates) can be attached, and an **AI assistant** can suggest a disposition — but
a human always applies it.

### How it works (developer)

**Entity `CovenantDisposition` (`cov_covenant_disposition`)** — **one row per (CP, covenant)**, updated in
place. Fields: `status` (`CovenantDispositionStatus`), `extensionDate`, `statusSource`
(`CovenantStatusSource SYSTEM/USER/AI`), `note`, and the **`assessmentId` it targets**.

**`CovenantDispositionStatus`:** `COMPLIED, BREACHED, WAIVER, EXTENSION`. Only **WAIVER/EXTENSION** are
user-recordable; COMPLIED/BREACHED are system-derived.

**The "active disposition" rule — `DispositionRules.addressesCurrentBreach(disp, latestAssessmentId)`:**
a disposition counts as *active/handled* only when it is **pinned to the latest assessment id** and its
status is WAIVER or EXTENSION. This is the single definition used by By-CP, By-Covenant, and the review
grid — so a waiver against a **superseded** assessment automatically becomes inert once a new assessment
runs.

**Recording a disposition — `ReviewService.setDisposition(cpId, appliedId, req)`** validation ladder:
1. status must be WAIVER/EXTENSION → else **400**.
2. EXTENSION with no date → **400**.
3. latest assessment missing / CP mismatch → **404**.
4. latest result **must be BREACHED** → else **409** (you can only dispose an *actual current* breach).
- `source` defaults to USER. Upserts the single `(cp, covenant)` row and appends an append-only
  `CovenantStatusAudit` entry (`changedBy` = current user or `"system"`) for full provenance (US-MON-19).

**Attachments (US-MON-17):** `AttachmentService` — max **10 MB**, allowed extensions
`pdf/doc/docx/xls/xlsx/csv/txt/png/jpg/jpeg`. Files are **never stored locally** — bytes are forwarded to
an external file service and only the returned `fileId` is persisted (`cov_covenant_attachment` per
covenant; `cov_compliance_certificate` at CP level). Upload failure → **502**.

**AI suggestions (US-MON-18):** `AiSuggestionService.suggestForCp(cpId)` sends the review rows +
certificate fileIds + master descriptions to an external recommender and returns per-covenant suggestions
(recommended status, confidence %, rationale). It **never throws** — an unavailable AI yields an empty
list and the UI shows "AI suggestions are unavailable". Applying a suggestion is a normal disposition
write tagged `source = AI`, and (like any disposition) only allowed on a breached row; an AI *extension*
still needs a date first.

**Live roll-ups (US-MON-22):** applying a disposition invalidates the By-CP summary, By-Covenant schedule,
and the review query, so all screens update immediately.

### Rules & scenarios

| Scenario | Behaviour |
|----------|-----------|
| Record WAIVER on a breach | Breach marked handled; timing unchanged. |
| Record EXTENSION without a date | Rejected (400). |
| Record a disposition on a non-breached covenant | Rejected (409). |
| A new assessment runs after a waiver | The waiver (pinned to the old assessment) becomes inert; if still breached the RM must re-dispose. |
| Upload a 12 MB file | Rejected client-side and server-side (10 MB cap). |
| AI recommender down | Empty suggestions; graceful UI message. |
| Apply an AI Extension with no date | Blocked until a date is chosen. |

---

## Part M — Reporting settings

*(User story US-AGG-11; `ReportingSettingsService`)*

### What it is / why it matters (BA)
A small set of **organisation-wide levers** that tune the reporting calendar and the monitoring windows.

### How it works (developer)

Singleton `cov_reporting_setting` mapped to `ReportingSettingsResponse`. Live fields + defaults:

| Setting | Default | Effect |
|---------|---------|--------|
| `fiscalYearStartMonth` | `1` | 1 = calendar year; 4 = Apr-start fiscal year, etc. Validated 1–12 (`400 INVALID_FISCAL_MONTH`). |
| `periodicIncludesCurrentPeriod` | `true` | Whether the in-progress period counts in PERIODIC aggregation windows. |
| `dueSoonDays` | `7` | The DUE_SOON horizon used by the timing calculator. |
| `upcomingWindowDays` | `30` | The By-Covenant agenda look-ahead window. |

`current()` returns the row or `defaults()`. `modifiedBy`/`modifiedDate` (from `AuditableEntity`) show who
last changed them. **Dead config to be aware of:** `startingSoonDays` exists on the entity **only** so
`ddl-auto: validate` passes — it is retired, not in the DTO, and not surfaced in the UI.

### Rules & scenarios

| Scenario | Behaviour |
|----------|-----------|
| No settings row persisted | Defaults `(1, true, 7, 30)` used everywhere. |
| Set fiscal start = 4 | Fiscal-year-aware periods start in April. |
| Raise `dueSoonDays` to 14 | More covenants flip to DUE_SOON sooner; agenda look-ahead unaffected. |
| Raise `upcomingWindowDays` to 60 | By-Covenant agenda shows a 60-day look-ahead. |
| Set fiscal month = 13 | Rejected (400). |

---

## Part N — RM/WRM portfolio scoping

*(Cross-cutting; `CurrentUserContext`, `CreditProposalCacheRepository`, `MonitoringService`)*

### What it is / why it matters (BA)
An RM only ever sees **their own** CPs. This is enforced **fail-closed** — if we cannot positively
identify the user, they see **nothing** (never everything).

### How it works (developer)
- Identity comes from the **`X-User`** request header (`CurrentUserContext`); a blank/absent header →
  `Optional.empty()`.
- Ownership: `CreditProposalCacheRepository.findCpIdsOwnedBy(user)` = CPs where
  `rm_user_id = user OR wrm_user_id = user` (from `cov_credit_proposal_cache`).
- `MonitoringService.ownedCpIds(user)` returns an **empty set** for a blank user — so every monitoring
  surface returns nothing rather than leaking cross-portfolio data. Drilling into a CP outside the
  portfolio also returns empty.
- In dev, a `DevUserSwitcher` lets you set the `X-User` header to test scoping.

### Rules & scenarios

| Scenario | Behaviour |
|----------|-----------|
| No `X-User` header | User sees **no** CPs (fail-closed). |
| RM signed in | Sees CPs where they are RM **or** WRM. |
| RM requests a CP they don't own | Empty result (not a 403 leak — just nothing). |

---

## Part O — Support / Ops toolkit

*(`Ops*` services + controllers under `/api/v1/ops`, token-gated)*

### What it is / why it matters (BA)
A protected toolkit for support engineers to answer *"why didn't this covenant evaluate?"*, check
data-source health, force a re-ingest/re-assess, purge a CP's data, and read an audit trail. **Everything
here is gated behind a support token** and every write is audited.

### How it works (developer)

**Gate:** `SupportTokenInterceptor` on `/api/v1/ops/**`, header **`X-Support-Token`**:
- support disabled → **503 SUPPORT_DISABLED**; header missing → **401 SUPPORT_TOKEN_MISSING**; wrong token
  → **403 SUPPORT_TOKEN_INVALID** (constant-time comparison). Config under `covenant.support`
  (`enabled` default true, `token`).

**Services & endpoints:**

| Service | Purpose | Key endpoints |
|---------|---------|---------------|
| `OpsDiagnosticsService` (read) | Explain why each formula term did/didn't resolve. Classifies each term with a `DiagnosticReason` (`OK, NO_OBLIGOR_MAPPING, MEASURE_NOT_REGISTERED, STAGING_EMPTY_INGEST_STATE_STALE, STAGING_EMPTY_NO_STATE, ITEM_CODE_NOT_STAGED, WINDOW_EMPTY`). | `GET /cps/{cpId}/diagnostics`, `/assessments/{id}/diagnostics`, `/cps/{cpId}/staging?tableId=`, `/cps/{cpId}/ingestion-state` |
| `OpsHealthService` (read) | Probe each data source: reachable? latency? table & staging-row counts? Backs the Actuator `dataSources` indicator. | `GET /health/data-sources?activeOnly=` |
| `OpsRemediationService` (write+audit) | Force `reingestCp`, `clearIngestionState`, or `reassess` (a MANUAL-triggered re-run). Skips obligor tables with no mapping (warns). | `POST /cps/{cpId}/reingest`, `DELETE /cps/{cpId}/ingestion-state?tableId=`, `POST /applied-covenants/{id}/reassess` |
| `OpsAuditService` (write) | Append-only `cov_ops_audit` in a `REQUIRES_NEW` tx (survives caller rollback). | `GET /audit?targetType=&targetId=` (targetType required → else 400) |
| `CpDataCleanupService` (destructive) | Delete **all** of a CP's data across ~16 tables (children→parent). **Obligor-shared staging is only deleted if the CP is the obligor's sole member** (else warns). | `DELETE /cps/{cpId}/data` — body `confirmCpId` must equal the path id, else **400 CP_CONFIRM_MISMATCH** |

**Ingestion plumbing exposed here:** `StagingIngestionService` (lands rows into `cov_staging_value`;
idempotent delete-then-insert), `IngestionOrchestrationService` (the freshness gate from Part B).

### Rules & scenarios

| Scenario | Behaviour |
|----------|-----------|
| Diagnose a covenant that ERRORed on missing data | Per-term reasons, e.g. `NO_OBLIGOR_MAPPING` or `WINDOW_EMPTY`, pinpoint the cause. |
| Health check with `activeOnly=true` | Only active sources probed; "N/M reachable". |
| Reassess after fixing a mapping | New assessment row; audited. |
| Cleanup with a mismatched confirm id | Rejected (400). |
| Cleanup a CP sharing an obligor with others | CP-level rows deleted; shared obligor staging preserved with a warning. |
| Any ops write | An append-only audit row is written even if the business tx rolls back. |

---

## Part P — Frontend screens

*(React 18 / Mantine v8 / Vite; navigation is a `useState` state-machine in `App.tsx` — **no URL
routes**.)*

**Sidebar (`NAV_ITEMS`):** Covenant Library · Measure Library · Formula Builder · Data Sources ·
CP Onboarding · Monitoring · Reporting Settings · Production Support. Detail views
(`covenant-detail`, `cp-assessment`, `assessment-detail`, …) are reached by drilling in. A `DevUserSwitcher`
appears in dev to set the `X-User` header. Shared TanStack Query defaults: `staleTime 30s`, `retry 1`,
`refetchOnWindowFocus false`.

| Area | Screen | What it shows / does |
|------|--------|----------------------|
| **Monitoring** | `MonitoringWorkspace` | Segmented "By CP" / "By Covenant"; remembers the choice in `sessionStorage`. |
| | `MonitoringDashboard` (By-CP) | Portfolio scorecard; Breached cell annotated "(N handled)"; "Run & notify" button. |
| | `MonitoringSchedulePage` (By-Covenant) | Agenda/Calendar; OVERDUE / DUE_WEEK / UPCOMING buckets; "Hide handled" switch; portfolio-health card. |
| | `CpDetailPage` | CP drill-in: summary tiles, chip filters, one card per covenant, "Run now" + History. |
| | `CpAssessmentPage` | The **review grid** (US-MON-16): expression, actual, headroom, status+provenance, attachments, "AI suggestions", compliance-certificate panel. |
| | `DispositionModal` | Record Waiver/Extension (date required for Extension); invalidates all monitoring roll-ups. |
| | `AiSuggestionModal` | Per-row AI suggestion + confidence + rationale; graceful "unavailable". |
| | `AssessmentDetailPage` | One assessment: definition-vs-executed formula, verdict, tracked value, measured-values snapshot, error alert, workflow link; **manual-completion card** for PENDING_MANUAL. |
| | `AssessmentHistoryPage` | Newest-first timeline of a covenant's assessments. |
| **Onboarding** | `CpOnboardingWizard` | Lookup → Review & Configure → Completed; merged recommendation grid; per-row threshold/frequency/type/classification/start; add-from-library / add-custom. |
| **Settings** | `ReportingSettingsPage` | Fiscal calendar, "include current period", `dueSoonDays`, `upcomingWindowDays`; last-modified info. |
| **Support** | `SupportWorkspace` (+ `CpDiagnosticsPanel`, `HealthPanel`, `AuditPanel`, `CleanupPanel`) | Token-gated tabs mirroring Part O; Cleanup requires typing the CP id to confirm. |
| **Authoring** | `CovenantLibraryPage` / `CovenantDetailPage` / `CovenantFormPage` | Browse/inspect/author covenants; formula builder with live validation; impact-guard on formula edits. |
| | `MeasureLibraryPage` / `MeasureFormPage` | Browse/author measures; COLUMNAR (source column) vs KEY_VALUE (source code) mapping. |
| | `DataSourceListPage` / `DataSourceFormPage` / `DataSourceTableListPage` / `DataSourceTableFormPage` | Register REST/MSSQL sources; "Test connection"; define tables (layout + entity-key level). |
| | `FormulaBuilderPage` | Standalone formula playground with live validate + dry-run. |

---

## Part Q — Cross-cutting rules & edge cases

A consolidated catalogue of the rules that span the whole app — the "gotchas" a BA or developer must know
in advance.

### Cross-cutting rules
1. **`cov_` table prefix** is mandatory for every app/audit table (Quartz `QRTZ_*`, Flyway history, and the
   demo `cp_metrics_demo` source are the only exceptions).
2. **"All" / empty multiselect = wildcard** in eligibility matching (dimension omitted). Islamic-banking is
   the one **exact-match, no-wildcard** dimension.
3. **AUTO evaluation never yields NA** — every missing-data / error path becomes **ERROR**. **NA is
   manual-only** and needs a note.
4. **Append-only assessments** — every run is a new row; completed rows are immutable.
5. **Portfolio scoping is fail-closed** — no identity ⇒ no data.
6. **Everything external is resilient** — CP lookup, ML/AI, notifications, workflow, file upload all
   degrade gracefully and never crash an assessment or a sweep.
7. **One disposition row per (CP, covenant)**, and it only "counts" while pinned to the *latest*
   assessment.
8. **Extension defers timing; Waiver does not.**
9. **DDL must run on both SQL Server and H2 (`MODE=MSSQLServer`).**
10. **Staging is the read model** — assessment reads only `cov_staging_value`, never the live source.

### Master edge-case catalogue

| # | "What happens if…" | Answer |
|---|--------------------|--------|
| 1 | …the data source is unreachable during an AUTO assessment? | ERROR `DATA_SOURCE_FAILURE: …`; the row is saved COMPLETED-with-ERROR; the workflow case (opened first) is unaffected. |
| 2 | …there are no rows / a null value for a measure? | ERROR `Missing values for measures: […]`. |
| 3 | …the covenant needs an obligor-keyed table but the CP has no obligor? | That table is skipped at ingest → measures missing → ERROR (case 2). |
| 4 | …a formula divides by zero? | ERROR `Evaluation error: Division by zero`. |
| 5 | …a covenant is MANUAL? | Stays PENDING_MANUAL until a human completes it (COMPLIANT/BREACHED/NA + required note). |
| 6 | …the workflow engine is down when assessment starts? | Case creation records an error; **assessment still completes**; `workflow_error` stored. |
| 7 | …the CP's RM has no email at sweep time? | CP is assessed but **not** emailed; a single aggregate FAILED audit row is written. |
| 8 | …a user has no `X-User` header? | They see nothing (fail-closed scoping). |
| 9 | …an RM waives a breach, then a new assessment runs and still breaches? | The old waiver becomes inert (pinned to the superseded assessment); the RM must re-dispose. |
| 10 | …an extension date is earlier or later than the next frequency date? | A *future, earlier* frequency date preempts the extension; otherwise the extension date governs (Part K). |
| 11 | …the ML/AI service is unavailable? | Empty suggestions/thresholds; the workflow proceeds with MASTER defaults; UI shows a graceful message. |
| 12 | …the app restarts mid-schedule? | Durable Quartz jobs + the two singletons survive and are re-registered idempotently; missed fires use fire-and-proceed. |
| 13 | …the same daily sweep is run twice? | Idempotent — already-assessed covenants are no longer due. |
| 14 | …a covenant is EXPIRED? | No schedule teardown; it is simply never "due" and is skipped by the ACTIVE-only sweep. |
| 15 | …a covenant is SUSPENDED? | Its Quartz job is paused and the sweep skips it. |
| 16 | …you edit a formula that's live on many CPs? | UI confirmation gate + change-log; in-flight/finished assessments keep their own formula snapshot. |
| 17 | …you upload a disposition file > 10 MB or a disallowed type? | Rejected on both client and server. |
| 18 | …you try to dispose a covenant that isn't currently breached? | 409 — dispositions are only for actual current breaches. |
| 19 | …a support engineer tries an Ops call without a token? | 401 (missing) / 403 (invalid) / 503 (support disabled). |
| 20 | …you purge a CP that shares an obligor with other CPs? | CP-level rows deleted; shared obligor staging preserved with a warning. |

---

## Appendix 1 — Enum reference

| Enum | Values | Notes |
|------|--------|-------|
| `CovenantMaster.category` | `FINANCIAL, NON_FINANCIAL` | Descriptive; drives UI (hides value/headroom for non-financial). |
| `AssessmentType` | `AUTO, MANUAL` | Drives the evaluation branch. |
| `AssessmentTrigger` | `SCHEDULED, MANUAL` | How a run was triggered (recorded on the row). |
| `AssessmentStatus` | `PENDING, IN_PROGRESS, COMPLETED, PENDING_MANUAL` | `PENDING` is a JPA default never left resting. |
| `AssessmentResult` | `COMPLIANT, BREACHED, NA, ERROR, PENDING_REVIEW` | NA = value-only (manual). |
| `AppliedCovenantStatus` | `ACTIVE, SUSPENDED, EXPIRED` | Drives scheduling & sweep inclusion. |
| `Frequency` | `DAILY, WEEKLY, MONTHLY, QUARTERLY, YEARLY, CUSTOM` | Each carries a default cron (CUSTOM = null). |
| `TimingStatus` | `NOT_STARTED, UP_TO_DATE, DUE_SOON, OVERDUE, ENDED` | The monitoring badge. |
| `CovenantDispositionStatus` | `COMPLIED, BREACHED, WAIVER, EXTENSION` | Only WAIVER/EXTENSION user-recordable. |
| `CovenantStatusSource` | `SYSTEM, USER, AI` | Disposition provenance. |
| `ThresholdSource` | `MASTER, USER, ML` | Threshold-number provenance. |
| Grid `source` (onboarding) | `RECOMMENDED, ML, MANUAL, CUSTOM` | UI origin label. |
| `RecommendationStatus` | `SUGGESTED, ACCEPTED, REJECTED, MANUAL_ADD` | Recommendation lifecycle. |
| `conditionType` | `PRECEDENT, SUBSEQUENT` | On applied covenant. |
| `classification` | `EXTERNAL, INTERNAL` | On applied covenant. |
| `DataSource.type` | `REST, MSSQL` | — |
| `DataSourceTable.layout` | `COLUMNAR, KEY_VALUE` | **No "ROW" layout.** |
| `DataSourceTable.entityKeyLevel` | `CP_ID, OBLIGOR_ID` | Obligor grain needs a mapping. |
| `FunctionKind` | `AVG, SUM, MIN, MAX, LATEST, CURRENT, COUNT` | Aggregation functions. |
| Window units | `D, W, M, Q, Y` | M/Q/Y are period-capable. |
| `SamplingMode` | `FLAT, PERIODIC` | Periodic honours `periodicIncludesCurrentPeriod`. |
| `DiagnosticReason` | `OK, NO_OBLIGOR_MAPPING, MEASURE_NOT_REGISTERED, STAGING_EMPTY_INGEST_STATE_STALE, STAGING_EMPTY_NO_STATE, ITEM_CODE_NOT_STAGED, WINDOW_EMPTY` | Ops term diagnosis. |
| `OpsAuditOutcome` | `SUCCESS, FAILURE` | Persisted lowercase. |
| `NotificationAudit.Outcome` | `SUCCESS, FAILED` | Daily-run email audit. |

---

## Appendix 2 — Database tables

**Core:** `cov_covenant_master`, `cov_measure`, `cov_data_source`, `cov_data_source_table`,
`cov_applied_covenant`, `cov_assessment`, `cov_assessment_threshold`, `cov_covenant_recommendation`,
`cov_monitoring_schedule`, `cov_reporting_setting`, `cov_credit_proposal_cache`, `cov_obligor_cp_map`.

**Data/ingestion:** `cov_staging_value` (the EAV read model), `cov_ingestion_state`.

**Dispositions/evidence/audit:** `cov_covenant_disposition`, `cov_covenant_status_audit`,
`cov_covenant_attachment`, `cov_compliance_certificate`, `cov_applied_covenant_audit`,
`cov_expression_change_log`, `cov_threshold_audit`, `cov_notification_audit`, `cov_ops_audit`.

**Not `cov_`-prefixed (intentionally):** the 11 Quartz `QRTZ_*` tables, `flyway_schema_history`, and the
dev-only demo source `cp_metrics_demo`.

`cov_assessment` key columns: `id`, `applied_covenant_id`, `scheduled_at`, `executed_at`,
`data_snapshot_json` (present values only), `evaluated_expression`, `condition_expression`,
`computed_value` (+type), `result`, `error_message`, `assessment_note`, **`workflow_instance_id`** (the
workflow caseId), `workflow_error`, `triggered_by`, `triggered_by_user`, `status`.

---

## Appendix 3 — User-story map

30 stories across 6 epics (specs in `_userstories/`). Quick index of where each is implemented in this
guide:

| Story | Title | Part |
|-------|-------|------|
| US-CM-01 | Covenant eligibility fields | A |
| US-CM-02 | Multiselect "All" wildcard | A, D |
| US-CM-03 | Assessment type AUTO/MANUAL | A, G |
| US-CM-22 | CP-scoped custom covenant | A, E |
| US-CM-23 | Formula builder | A, C |
| US-DATA-06 | Reference data & naming | A, B |
| US-DS-10 | Row / key-value data source | B |
| US-DS-11 | Data-source onboarding journey | B |
| US-DS-12 | Multi-year REST series fetch | B |
| US-DS-13 | Freshness-gated ingestion | B, O |
| US-AGG-11 | Period-aware windowed aggregation | C, M |
| US-REC-04 | Recommendation matching | D |
| US-ML-12 | ML-recommended thresholds | D |
| US-ML-13 | Threshold provenance & audit | D |
| US-ML-20 | Auto master + ML on lookup | D, E |
| US-FREQ-07 | Covenant frequency & start date | E, F |
| US-ASM-05 | Manual assessment execution | G |
| US-ASM-21 | Decomposed assessment comparisons | G |
| US-MON-08 | CP covenant monitoring timeline | J, K |
| US-MON-09 | Portfolio monitoring schedule | J, K |
| US-MON-14 | Daily breach run & notifications | I |
| US-MON-15 | Daily monitoring workspace | J, P |
| US-MON-16 | CP assessment grid & disposition | J, L |
| US-MON-17 | Evidence attachments & certificate | L |
| US-MON-18 | AI status suggestions | L |
| US-MON-19 | Status provenance & audit | L |
| US-MON-20 | Disposition-aware By-CP view | J, L |
| US-MON-21 | By-Covenant agenda disposition badges | J, K, L |
| US-MON-22 | Disposition refreshes portfolio views | J, L |

*(036 append-only assessments is a foundational rule, see Part G.)*

---

## Appendix 4 — Build, test & quality gates

Nothing is on `PATH` — use absolute tool paths and set `JAVA_HOME` before Maven.

**Backend**
- Fast loop (skips ITs/SpotBugs/OWASP): `mvn -P fast test`
- Full gate (no NVD network): `mvn -P no-owasp verify`
- Run with dev demo data (port 9310): `mvn -P fast "-Dspring-boot.run.profiles=dev" spring-boot:run`
- Gates: **JaCoCo 0.95 line / 0.90 branch**, SpotBugs + FindSecBugs (Max/Low), OWASP (`failBuildOnCVSS 7`).
  DTOs (`*Request`/`*Response`/`dto/**`) are coverage-excluded; Mockito runs `STRICT_STUBS`.

**Frontend** (in `frontend/`)
- `npm run type-check` · `npm run test` (or `test:coverage`) · `npm run lint` · `npm run build`
- Gates: ESLint `--max-warnings=0` + a security config, `audit-ci`, Vitest coverage
  **95 stmt / 85 branch / 75 func / 95 line** (`hooks/**`, `types/**` excluded).

**Profiles / ports:** dev & test use **H2 in `MODE=MSSQLServer`**; prod targets **SQL Server**. Dev app on
port **9310**; dev WireMock stubs (external services) on **9311**.

---

*End of guide. This document reflects the code as of branch `feat/mon-disposition-aware-views`. When a
feature changes, update the relevant Part and the edge-case catalogue in Part Q so this stays the single
source of truth for client discussions.*
