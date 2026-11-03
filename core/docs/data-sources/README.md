# Data Sources — configuration & usage guide

How to register the systems that feed covenant monitoring, how to configure each **type** and
**shape** of source, and eight end‑to‑end worked examples that grow from *simple* → *complex* →
*very complex*. Every example is grounded in the real dev demo seed (`db/demo/V10__demo_data.sql`, the
ingestion extension `db/demo/V20__ingestion_demo_data.sql`, and the REST obligor‑series extension
`db/demo/V21__rest_obligor_demo_data.sql`) so you can reproduce it against the running app.

> **Enum casing.** Values are stored **lowercase** in the database (CHECK constraints + JPA
> converters) but the REST API and this guide use **UPPERCASE** (`MSSQL`, `COLUMNAR`, `CP_ID`,
> `NUMBER`, `LATEST`, …). Always send UPPERCASE in JSON payloads.

**See also:** [data-model cheat‑sheet](../demo/data-model-cheatsheet.md) ·
[US‑DS‑10 row/key‑value source](../../_userstories/US-DS-10-row-key-value-data-source.md) ·
[US‑AGG‑11 windowed aggregation](../../_userstories/US-AGG-11-period-aware-windowed-aggregation.md)

---

## Table of contents
1. [The mental model](#1-the-mental-model)
2. [The three configuration axes](#2-the-three-configuration-axes)
3. [How data flows at assessment time](#3-how-data-flows-at-assessment-time)
   - [3.1 The freshness gate](#31-the-freshness-gate) ·
     [3.2 Pre‑stage sweep](#32-due-driven-pre-stage-sweep) ·
     [3.3 Multi‑year REST series](#33-multi-year-rest-series-us-ds-12) ·
     [3.4 Config knobs](#34-ingestion-configuration-knobs) ·
     [3.5 Missing measure values](#35-when-a-referenced-measure-has-no-value)
4. [Configuration reference by type](#4-configuration-reference-by-type)
5. [Field reference (table registration)](#5-field-reference-table-registration)
6. [Measures — mapping storage to a formula name](#6-measures--mapping-storage-to-a-formula-name)
7. [The formula & aggregation DSL](#7-the-formula--aggregation-dsl)
8. [UI walkthrough](#8-ui-walkthrough)
9. [Worked examples (simple → very complex)](#9-worked-examples)
   - [Example 1 — Simple: columnar MSSQL, CP‑keyed](#example-1--simple-columnar-mssql-cp-keyed)
   - [Example 2 — Intermediate: row / key‑value MSSQL](#example-2--intermediate-row--key-value-mssql)
   - [Example 3 — Complex: REST API source](#example-3--complex-rest-api-source)
   - [Example 4 — Very complex: obligor‑keyed + windowed aggregation](#example-4--very-complex-obligor-keyed--windowed-aggregation)
   - [Example 5 — The freshness gate in action (assess twice)](#example-5--the-freshness-gate-in-action-assess-twice)
   - [Example 6 — Multi‑year windowed aggregation (first‑run history)](#example-6--multi-year-windowed-aggregation-first-run-history)
   - [Example 7 — REST obligor‑keyed, mixed annual/quarterly series](#example-7--rest-obligor-keyed-mixed-annualquarterly-series)
   - [Example 8 — Obligor‑level REST feed, many data points (frontend walkthrough)](#example-8--obligor-level-rest-feed-with-many-data-points-full-frontend-walkthrough)
10. [Validation rules & troubleshooting](#10-validation-rules--troubleshooting)
11. [REST endpoint quick reference](#11-rest-endpoint-quick-reference)

---

## 1. The mental model

A data source is registered once, then progressively narrowed down until each covenant term resolves
to a single number (or flag) for one Credit Proposal (CP):

```
DataSource            a named connection             (MSSQL table  |  REST endpoint)
   └── DataSourceTable a table/endpoint + its shape   (COLUMNAR | KEY_VALUE, CP_ID | OBLIGOR_ID)
         └── Measure   a formula identifier → storage (source_column  |  source_code)
               └── referenced by a Covenant formula   e.g.  DSCR >= 1.50
                     └── evaluated per CP → Assessment (COMPLIANT | BREACHED | NA | …)
```

Four ideas make the rest of this guide click:

- **A durable copy is kept, and ingestion is gated.** The system *ingests* the source rows it needs
  into a staging store (`cov_staging_value`) and evaluates against that mirror — never against the live
  source mid‑query. Ingestion happens **ahead of need and only when the copy is missing or stale** (a
  freshness gate + a pre‑stage sweep, [§3](#3-how-data-flows-at-assessment-time)); re‑running a day
  overwrites the copy rather than duplicating it (idempotent).
- **The immutable per‑assessment snapshot is separate.** Every completed assessment still records its
  own `data_snapshot_json` audit copy (US‑036); that is never mutated. Staging is the *working* copy;
  the snapshot is the *evidence* copy.
- **Everything resolves at CP grain.** Even when data arrives at *obligor* grain, results attach to a
  CP. An obligor‑keyed table resolves the CP's obligor once, reads the shared obligor data, and fans
  it out to every CP under that obligor.
- **Measures decouple the formula from storage.** Covenant authors write `DSCR`; whether that lives in
  a physical `dscr` column or a `metric_code='DSCR'` row is a mapping detail on the measure.

---

## 2. The three configuration axes

Every table you register is described by three independent choices:

| Axis | Values | What it means | Where |
|------|--------|---------------|-------|
| **Type** | `MSSQL` · `REST` | Physical protocol: a JDBC table you copy, or an HTTP endpoint you fetch. | `DataSource.type` |
| **Layout / shape** | `COLUMNAR` · `KEY_VALUE` | Whether each measure is its own **column**, or many measures share one value column keyed by a **code**. | `DataSourceTable.layout` |
| **Entity‑key level / grain** | `CP_ID` · `OBLIGOR_ID` | Whether a row is keyed by the CP directly, or by the obligor (shared across the obligor's CPs). | `DataSourceTable.entityKeyLevel` |

These combine freely — e.g. a `REST` + `COLUMNAR` + `CP_ID` attribute feed, or an
`MSSQL` + `KEY_VALUE` + `OBLIGOR_ID` group‑level ratio warehouse.

### COLUMNAR vs KEY_VALUE at a glance

```
COLUMNAR                                   KEY_VALUE (row / EAV)
cp_id  as_of_date  dscr  net_leverage      cp_id  as_of_date  metric_code   metric_value
CP-1   2026-03-31  1.85  2.90              CP-1   2026-03-31  CURRENT_RATIO 1.85
                                           CP-1   2026-03-31  DEBT_EQUITY   1.10
measure DSCR → source_column = "dscr"      measure CURRENT_RATIO → source_code = "CURRENT_RATIO"
```

- **COLUMNAR** — the classic wide table: one physical column per measure. Register a measure with a
  `sourceColumn`. Best when the source has a stable, known set of columns.
- **KEY_VALUE** — the tall/EAV shape: one row per `(entity, as‑of, code)` with a single shared value
  column. The table declares a **code column** and a **value column**; each measure maps to a distinct
  code via `sourceCode`. Best when metrics are numerous, sparse, or added without schema changes.

---

## 3. How data flows at assessment time

```
   pre-stage sweep (ahead of need)              assessment run (per applied covenant)
 ┌───────────────────────────────────┐   ┌──────────────────────────────────────────────────┐
 due covenants ─▶ coalesce by entity  │   │  gated ingest ─▶ cov_staging_value ─▶ resolver ─▶  │
 ─▶ freshness gate ─▶ MSSQL copy /    │   │  (skip if fresh)   (entity_key +      (LATEST/AVG/  │
    REST series fetch ─▶ staging      │   │                     as_of_date +       PERIODIC…)   │
 └───────────────────────────────────┘   │                     item_code)                       │
                                          │  ─▶ formula (DSCR>=1.50) ─▶ Assessment + data_snapshot│
                                          └──────────────────────────────────────────────────────┘
```

1. **Gated, just‑ahead‑of‑need ingestion (US‑DS‑13).** Ingestion is a *decision*, not a blind fetch.
   Both the pre‑stage sweep and the assessment run call the **freshness gate**
   (`IngestionOrchestrationService.ingestIfStale`) once per distinct `(table, entityKey)`; it issues an
   extract **only** when the staged copy is missing, stale, or the source changed — otherwise it skips
   (see [§3.1](#31-the-freshness-gate)). So the first covenant on an entity lands the copy and the ~500
   that follow on the same entity read it locally.
   - **MSSQL** — reads the entity's rows over a **pooled** JDBC connection (`JdbcDataSourceRegistry`,
     [§4.1](#41-mssql-sources)). A point‑in‑time term copies only the latest row(s) `<= as‑of`; a
     **windowed** term copies the `[effectiveFrom, asOf]` window — the *whole* window on the first run,
     then only dates **newer** than the staged watermark, and a full re‑fetch when the look‑back reaches
     back *before* the earliest staged date (see the shared cursor below).
   - **REST** — both point‑in‑time and windowed terms hit **one** as‑of‑aware endpoint
     (`POST {baseUrl}/api/data/values`, [§3.3](#33-multi-year-rest-series-us-ds-12)) routed to the
     *source's own* base URL. A point‑in‑time term omits `from` (the source returns the latest point
     `<= to`); a windowed term sends `from` and gets the `[from, to]` series — pulling only dates newer
     than what is already staged (US‑DS‑12).
   - **Shared incremental cursor.** Both types resolve the fetch floor the same way (`effectiveFrom`):
     *reach‑back* — if the requested `from` is earlier than the earliest staged as‑of, refetch the full
     `[from, asOf]` window; else *forward‑incremental* — if the staged watermark is at/after `from`,
     fetch only `watermark + 1 day … asOf`; else (first run) fetch `[from, asOf]`.
   - Idempotent: within one transaction it deletes any prior copy for the entity across exactly the
     as‑of dates it re‑reads, then inserts fresh rows (`uk_staging_value` is the anchor).
2. **Uniform resolve.** The resolver reads *only* from `cov_staging_value` for every source type and
   **never calls the source mid‑evaluation**: `WHERE data_source_table_id = ? AND entity_key = ? AND
   item_code = ?` plus the as‑of window bounds. `item_code` is the measure's `sourceColumn` (COLUMNAR)
   or `sourceCode` (KEY_VALUE) — one predicate for both shapes. The typed value column (`num_value` /
   `str_value` / `date_value` / `bool_value`) is chosen by the measure's `dataType`.
3. **Aggregate.** `LATEST`/`CURRENT` → `TOP 1 … ORDER BY as_of_date DESC`; `AVG`/`SUM` cast to
   `DECIMAL(38,6)`; `PERIODIC` collapses each reporting period to its period‑end figure before
   aggregating (via `ROW_NUMBER() OVER (PARTITION BY period …)`).
4. **Attach & snapshot.** The result attaches to the CP's assessment and the immutable
   `data_snapshot_json` is written.

### 3.1 The freshness gate

For each `(entity_key, data_source_table)` the gate resolves three inputs and decides ingest‑or‑skip
(reasons are recorded on a `cov_ingestion_state` control row so the decision is cheap and auditable):

| Input | Meaning |
|-------|---------|
| `required_as_of` | The ceiling the caller needs — the assessment date / widest aggregation window. |
| `staged_as_of` | The max `as_of_date` already staged for that entity + source (the watermark). |
| `source_changed_at` | When the source last revised data. A **REST** source echoes it in each `DataSeriesResponse` and the gate stamps it on the control row on success; **MSSQL** change‑detection is deferred → `null` (MSSQL relies on the TTL). Also settable via force‑refresh. |

> **Ingest** when `staged_as_of` is **missing**, **OR** `staged_as_of < required_as_of` (**stale**), **OR**
> `source_changed_at > staged_ingested_at` (**source‑changed**), **OR** the max‑staleness **TTL** has
> elapsed (safety re‑pull for sources with no change signal). **Otherwise skip** (**fresh**).

**Staging‑presence safety net.** Even on the *skip* branch the gate re‑checks that staging actually holds
rows for `(table, entity)`. If nothing is staged it ingests anyway with reason **`staging-empty`** rather
than trust a control row that claims *fresh*. This closes a stale‑fresh trap where an assessment could
otherwise fail with `ERROR: Missing values for measures [...]` against an empty staging table.

Consequence you can observe: **assess the same covenant twice on the same day** — the first run ingests
(`OK`), the second finds the copy fresh and **skips** (no source call). A **force‑refresh** stamps
`source_changed_at = now` so the next decision re‑pulls regardless (used for restatements / interactive
single‑CP refresh); it is an internal service capability (`IngestionOrchestrationService.forceRefresh`),
not yet a public REST endpoint.

### 3.2 Due‑driven pre‑stage sweep

Ingestion is orchestrated **independently of evaluation** on its own Quartz trigger (a separate failure
domain from the breach run). The pre‑stage sweep (`IngestionPreStageService`, job
`ingestion-pre-stage-run`, default cron `0 30 5 * * ?` — 30 min ahead of the 06:00 breach run):

1. Pages the **active** covenant book (500 at a time) and keeps only those **due within the lookahead
   horizon** (`covenant.ingestion.lookahead-days`, default 3).
2. **Coalesces** demand by `(table, entity)` — collapsing an obligor's CPs to one obligor‑grain extract
   — and keeps the **widest** (earliest) look‑back, so 500 covenants sharing one obligor become **one**
   candidate.
3. Runs the freshness gate per candidate; a failing entity is isolated (logged, counted) so one bad
   source cannot abort the sweep. The subsequent evaluation run then finds everything staged and reads
   locally.

If the sweep is disabled or a trigger can't register, evaluation still works — the assessment‑time gate
falls back to on‑demand ingestion.

### 3.3 Multi‑year REST series (US‑DS‑12)

A REST source is snapshot‑per‑call, so a windowed term must pull its whole look‑back to resolve on the
**first** assessment. Both point‑in‑time and windowed reads now go through **one** as‑of‑aware endpoint —
`POST {baseUrl}/api/data/values` — distinguished only by whether `from` is present:

- A **point‑in‑time** term (`LATEST`/`CURRENT`, no window) **omits** `from`; the source returns a single
  latest point `<= to`.
- A **windowed** term (e.g. `AVG(DSCR,'5y',PERIODIC)`) sends `from`; the source returns the `[from, to]`
  series, where `from` is derived from the widest window (fiscal‑boundary aligned).
- **Shared incremental cursor + reach‑back** (same `effectiveFrom` logic as MSSQL, [§3](#3-how-data-flows-at-assessment-time)):
  in steady state a run fetches only dates newer than the staged watermark; the full window is pulled on
  the first run or when the look‑back reaches back before the earliest staged date.
- Both shapes return a single `DataSeriesResponse` (a `points[]` array plus an optional `sourceChangedAt`).
  The older `/api/data/values/series` endpoint is **gone** — one contract serves both reads.

### 3.4 Ingestion configuration knobs

Externalised under `covenant.ingestion.*` (tune without a redeploy):

```yaml
covenant:
  ingestion:
    ttl-hours: 24            # max-staleness floor when a source can't report source_changed_at
    lookahead-days: 3        # how far ahead the pre-stage sweep looks for due covenants
    pre-stage-cron: "0 30 5 * * ?"   # Quartz cron for the pre-stage sweep (own failure domain)
    mssql-pool:              # HikariCP pool per MSSQL source (JdbcDataSourceRegistry, §4.1)
      maximum-pool-size: 4
      minimum-idle: 0
      connection-timeout-ms: 5000
```

### 3.5 When a referenced measure has no value

A covenant can name a measure (e.g. `DSCR`) for which the source has **no value** at assessment time.
The system treats a missing input as a **hard, explicit outcome** — never a silent pass, never a false
breach. The assessment completes with result **`ERROR`** and an `errorMessage` naming the measures that
could not be resolved.

**How the decision is made** (`AssessmentService.assess`):

1. The condition is compiled and its measure terms bound. If a referenced measure is **not registered /
   mis‑typed**, this step fails first → result `ERROR`, message `Formula error: …` — a *definition‑time*
   miss, before any data is fetched.
2. Otherwise the source is ingested and the resolver runs one windowed query per term against
   `cov_staging_value`. A term is **`present = false`** when the query finds **no row** (point‑in‑time,
   `SELECT TOP 1 … ORDER BY as_of_date DESC`) or the aggregate is **`null`** (`AVG`/`SUM`/`MIN`/`MAX`
   over an empty window).
3. Every term with `value == null || !present` is collected into a `missing` list. If that list is
   non‑empty the assessment short‑circuits with result `ERROR`, message
   `Missing values for measures: [DSCR]`, status `COMPLETED`. The formula is **not** evaluated, so a
   missing value can never be scored `COMPLIANT` or `BREACHED`.
4. The partial `data_snapshot_json` still records whichever measures *were* present, so the evidence copy
   shows exactly what was and wasn't available.

**Why a value can be missing** — four distinct causes, each with its own message:

| Root cause | Where it happens | Result · message |
|------------|------------------|------------------|
| `DSCR` not registered as a measure, or a typo in the formula | Formula compile / bind (before any fetch) | `ERROR` · `Formula error: …` |
| Source responded but had **no `dscr` datum** for that entity/date (REST omitted the key or returned `null`; MSSQL cell null / no row in the window) | Ingest lands **no staging row** → resolver `present=false` | `ERROR` · `Missing values for measures: [DSCR]` |
| Obligor‑keyed measure, but the CP has **no obligor mapping** | Resolver short‑circuits `present=false` | `ERROR` · `Missing values for measures: [DSCR]` |
| Source **unreachable / fetch threw** (couldn't even fetch) | Ingest / resolve throws | `ERROR` · `DATA_SOURCE_FAILURE: …` (upstream `502 DATA_API_FAILED`) |

The middle two both surface as `Missing values for measures: […]`; the difference is *why* nothing was
staged (the source had no datum vs. there was no obligor to key on).

**Worked example.** Covenant `DSCR >= 1.50` applied to `CP‑1001`; the source returns `net_leverage` but
**no `dscr`** for `2026‑03‑31`.

- **Ingest:** the REST point / MSSQL row lands a `net_leverage` staging row but **no `dscr` row** — the
  landing loop skips any key whose value is `null` or absent ([§4.2](#42-rest-sources)).
- **Resolve:** `LATEST(DSCR)` → `SELECT TOP 1 num_value … item_code = 'dscr' …` finds no row →
  `MeasureValue(present=false)`.
- **Assess:** `missing = [DSCR]` → result `ERROR`, `errorMessage = "Missing values for measures: [DSCR]"`,
  `data_snapshot_json` holds only the measures that resolved. **No breach is raised and no
  disposition/workflow is started.**

**Steps to observe (dev):**

1. Apply `DSCR >= 1.50` to a CP whose source lacks a `dscr` value (e.g. register a REST measure whose
   **Source column** doesn't match any key the stub returns).
2. **Monitoring → open the CP → Run now.** The covenant shows a gray **`Error`** badge (never green/red).
3. **Open the assessment** — a gray **Error** alert shows `Missing values for measures: [DSCR]`;
   **History** lists the same message in red, and the CP dashboard's **error** count increments.

**What it does *not* do:** it does not fall back to a stale value, does not assume compliance, and does
not raise a breach — a missing input is quarantined as `ERROR` so a human notices the data gap.

**Interaction with the staging‑presence safety net** ([§3.1](#31-the-freshness-gate)): the
`staging-empty` override forces a re‑ingest only when **nothing at all** is staged for the
`(table, entity)`; it cannot invent a `dscr` the source never returned, so a genuinely absent datum stays
`ERROR` on every re‑run until the source provides it.

**How to handle it:**

- **Expected‑manual measures** (the source can't supply them): set the covenant's **Assessment type** to
  **`Manual`** in the Covenant Library so an analyst completes it (`COMPLIANT`/`BREACHED`/`NA` + note),
  instead of auto‑assessing.
- **Data gap:** make the source publish `dscr` for that entity/date, and confirm the measure's **Source
  column/code** exactly matches the key it returns.
- **Obligor case:** look the CP up first (captures the obligor mapping) or backfill `cov_obligor_cp_map`.

> **Edge case.** `COUNT(DSCR, …)` over an empty window returns `0`, not `null`, so a *count* of a missing
> measure resolves to zero rather than `ERROR`; every other aggregate treats an empty window as missing.

---

## 4. Configuration reference by type

### 4.1 MSSQL sources

The **connection** is built from `DataSource.connectionConfig`; the **table + columns** to read live
on the `DataSourceTable` (Section 5). Only three connection keys are read today:

| Key | Required | Notes |
|-----|----------|-------|
| `jdbcUrl` | **yes** | Full JDBC URL. Missing/blank → `400 DATA_SOURCE_CONFIG_MISSING`. |
| `username` | no | Applied only when non‑blank. |
| `password` | no | Applied when present (may be empty string). |

> **Production credentials.** The `DataSource` entity documents a `secretRef` key as the intended way
> to avoid a plaintext `password` in production; the current JDBC factory reads `password` directly, so
> treat plaintext passwords as **dev/demo only** and inject real credentials via your secret store.

DDL note: the app targets SQL Server in prod and runs H2 in `MODE=MSSQLServer` for dev/test — so a
demo "external" MSSQL source can point at the app's own H2 file DB (see the examples).

**Pooled connections.** Each MSSQL source gets one long‑lived `HikariDataSource`, kept by
`JdbcDataSourceRegistry` and reused across ingests instead of opening a fresh connection per pull. The
pool is rebuilt (and the old one closed) whenever the source's `jdbcUrl`/`username`/`password` changes,
and every pool is closed on shutdown. Tune it under `covenant.ingestion.mssql-pool.*`
([§3.4](#34-ingestion-configuration-knobs)).

```jsonc
// POST /api/v1/data-sources   (MSSQL)
{
  "name": "Quarterly Financials Warehouse",
  "type": "MSSQL",
  "description": "Borrower quarterly financial metrics.",
  "connectionConfig": {
    "jdbcUrl": "jdbc:sqlserver://fin-dwh:1433;databaseName=RISK;encrypt=true",
    "username": "covenant_ro",
    "password": "••••••"
  }
}
```

### 4.2 REST sources

REST ingestion is **routed per source**: the fetch is issued against the source's *own* base URL, and
both point‑in‑time and windowed reads use **one** as‑of‑aware endpoint. The URL is resolved from the
`DataSource.connectionConfig`, falling back to the globally‑configured client when a key is absent:

| Key | Required | Notes |
|-----|----------|-------|
| `baseUrl` | no | The source's own base URL. When present, the fetch targets an **absolute** URI (`{baseUrl}{valuesPath}`), bypassing the global client base. When blank, the global `covenant.clients.data-api.base-url` is used. |
| `valuesPath` | no | Path of the values endpoint. Defaults to `/api/data/values`. |

```yaml
# application.yml  (global fallback base; application-test.yml uses :9319)
covenant:
  clients:
    data-api:
      base-url: http://localhost:9311    # used only when a source sets no baseUrl
```

The single contract the data API must implement — `from` is optional and selects the read shape:

```
POST {baseUrl}/api/data/values
# point-in-time (from omitted → source returns the latest point <= to)
Request : { "cpId": "CP-1001", "to": "2026-03-31", "keys": ["facilityAmount","tenorMonths"] }
# windowed (from present → source returns the [from, to] series)
Request : { "cpId": "CP-1001", "from": "2021-04-01", "to": "2026-03-31", "keys": ["dscr"] }
Response: { "cpId": "CP-1001",
            "points": [ { "asOfDate": "2022-03-31", "values": { "dscr": 1.70 } },
                        { "asOfDate": "2023-03-31", "values": { "dscr": 1.78 } }, … ],
            "missingKeys": [],
            "sourceChangedAt": "2026-03-31T00:00:00Z" }
```

- `keys` are the measures' `sourceColumn`/`sourceCode` values for that table. `cpId` carries whichever
  entity key the table declares (the CP id, or the obligor id for an `OBLIGOR_ID` table).
- Each `DataPoint` in `points` is landed as its own dated row‑set, typed by the measure's `dataType`,
  and is idempotent per as‑of date. An empty `points` list is not an error.
- `sourceChangedAt` (optional) is echoed onto the ingestion control row and feeds the *source‑changed*
  freshness decision on the next pull.
- A `null`/empty body → `502 DATA_API_EMPTY`; an upstream error → `502 DATA_API_FAILED`; the call has a
  5s timeout.

> A REST `DataSource` with **no** reachable endpoint stays **manual‑only** — its covenants are completed
> by a reviewer rather than auto‑fetched. To auto‑assess a REST source, implement
> `POST {baseUrl}/api/data/values` at its `baseUrl` (or at the global `data-api.base-url`).

---

## 5. Field reference (table registration)

`POST /api/v1/data-sources/{dataSourceId}/tables` — body = `CreateDataSourceTableRequest`:

| Field | Required | Applies to | Rule |
|-------|----------|------------|------|
| `name` | **yes** | all | Human label, unique within the source (≤ 255). |
| `tableName` | **yes** | MSSQL (nominal for REST) | SQL identifier `^[A-Za-z_][A-Za-z0-9_]*$` (≤ 128). |
| `entityKeyColumn` | **yes** | all | The grouping key column (e.g. `cp_id` / obligor id). SQL identifier. |
| `asOfColumn` | **yes** | all | The date column used for time‑window aggregation. SQL identifier. |
| `schemaName` | no | MSSQL | Optional schema (e.g. `dbo`). Blank or SQL identifier. |
| `layout` | no | all | `COLUMNAR` (default) or `KEY_VALUE`. |
| `codeColumn` | KEY_VALUE only | MSSQL | Column holding the measure code. Required for `KEY_VALUE`, forbidden for `COLUMNAR`. |
| `valueColumn` | KEY_VALUE only | MSSQL | Column holding the value. Required for `KEY_VALUE`, forbidden for `COLUMNAR`. |
| `entityKeyLevel` | no | all | `CP_ID` (default) or `OBLIGOR_ID`. |

**Service validation** (mirrored client‑side in the form):
- `KEY_VALUE` **must** supply both `codeColumn` and `valueColumn`; `COLUMNAR` must supply **neither** →
  otherwise `400 INVALID_TABLE_LAYOUT`.
- All identifiers must match the SQL‑identifier pattern (letters/digits/underscore, not starting with a
  digit). `schemaName`/`codeColumn`/`valueColumn` may be blank; `tableName`/`entityKeyColumn`/
  `asOfColumn` may not.
- Duplicate `name` within a source is rejected.

**Discovery caveat.** `GET …/tables/{tableId}/columns` and `…/code-values` only resolve for a *persisted*
table (the server must load the entity to introspect it). So in the UI, **create** mode uses free‑text
inputs; **edit** mode upgrades key/as‑of/code/value fields to searchable dropdowns populated from
discovery, and offers a "Preview distinct codes" button for KEY_VALUE tables.

---

## 6. Measures — mapping storage to a formula name

`POST /api/v1/measures` — body = `CreateMeasureRequest`:

| Field | Required | Rule |
|-------|----------|------|
| `code` | **yes** | The formula identifier used in covenant expressions. `^[A-Za-z_][A-Za-z0-9_]*$` (≤ 64). |
| `name` | **yes** | Human label (≤ 255). |
| `dataSourceTableId` | **yes** | The table this measure reads from. |
| `defaultAggregation` | **yes** | `LATEST` · `CURRENT` · `AVG` · `SUM` · `MIN` · `MAX` · `COUNT`. |
| `dataType` | **yes** | `NUMBER` · `STRING` · `DATE` · `BOOLEAN` (selects the staging value column). |
| `sourceColumn` | COLUMNAR | The physical column name (COLUMNAR tables). |
| `sourceCode` | KEY_VALUE | The code value (KEY_VALUE tables), e.g. `CURRENT_RATIO`. |
| `description`, `unit` | no | Free text / display unit (e.g. `x`, `INR cr`). |

Rule of thumb: a measure on a **COLUMNAR** table supplies `sourceColumn`; a measure on a **KEY_VALUE**
table supplies `sourceCode`. That single value becomes the `item_code` under which the measure is
stored and read in staging.

---

## 7. The formula & aggregation DSL

Covenant conditions are boolean expressions over measures. The simplest form references a measure by
its `code` (implicitly its most‑recent value) and compares it:

```
DSCR >= 1.50
NET_LEVERAGE <= 3.50
EXPORT_LICENCE_VALID == true
```

Measures can be aggregated over a **rolling time window**, with an optional **sampling** mode:

```
FUNCTION( MEASURE , 'WINDOW' [, SAMPLING] )
```

| Part | Values |
|------|--------|
| `FUNCTION` | `AVG` `SUM` `MIN` `MAX` `COUNT` `LATEST` `CURRENT` (`AVG/SUM/MIN/MAX` need a numeric measure). |
| `WINDOW` | `<amount><unit>`, amount `1..1000`, unit `d` day · `w` week · `m` month · `q` quarter · `y` year. E.g. `'90d'`, `'4q'`, `'2y'`. |
| `SAMPLING` | `FLAT` (default — every reading in the window) · `PERIODIC` (period‑end per period) · `PERIODIC_CURRENT` (always include the in‑progress period) · `PERIODIC_COMPLETED` (completed periods only). Period sampling needs a period‑capable unit: `m`/`q`/`y`. |

Examples:

```
AVG(DSCR, '4q', PERIODIC) >= 1.50      -- avg of the last 4 quarter-end DSCRs
MIN(INTEREST_COVER, '1y') >= 4.00      -- worst monthly reading over a year (FLAT)
SUM(CRUDE_OUTPUT, '4q', PERIODIC_COMPLETED) >= 24.0
```

---

## 8. UI walkthrough

Run the app in dev and open **http://localhost:9310**
(`mvn -P fast "-Dspring-boot.run.profiles=dev" spring-boot:run`).

1. **Data Sources** (left nav) → **New data source**. Pick the type (`MSSQL`/`REST`), give it a name,
   and fill `connectionConfig`. For MSSQL you can use **Test connection** and, once saved, browse
   discovered columns.
2. Open the source → **Tables** → **Register table**.
   - Set **Name**, **Table name**, **Entity key column**, **As‑of column**.
   - Choose **Mapping shape** with the `Columnar` ↔ `Row / Key‑Value` toggle. Selecting *Row/Key‑Value*
     reveals **Code column** + **Value column** (and, in edit mode, **Preview distinct codes**).
   - Choose **Entity key level** with the `CP_ID` ↔ `Obligor` toggle.
   - Save.
3. **Measure Library** → **New measure**. Select the table, set `code`, `dataType`,
   `defaultAggregation`, and either `sourceColumn` (columnar) or `sourceCode` (key‑value).
4. **Covenant Library** → author or reuse a covenant whose formula references your measure code(s).
5. **Onboarding / CP** → look up a CP (this captures its obligor, if any), apply the covenant with a
   frequency, then run an assessment. Financial covenants complete automatically; non‑financial ones
   return `PENDING_REVIEW` for manual completion.

---

## 9. Worked examples

Each example gives the **UI path** and the **equivalent REST calls**. Base URL below is the dev host
`http://localhost:9310`. IDs returned by `POST`s are echoed back as `{ "id": "…" }`; substitute them
into later calls.

---

### Example 1 — Simple: columnar MSSQL, CP‑keyed

**Goal:** monitor *Debt Service Coverage Ratio* per CP: `DSCR >= 1.50`. This is the seeded
*"Demo MSSQL Financials"* journey.

**The source data** (`cp_metrics_demo`, one wide row per CP per quarter):

| cp_id | as_of_date | dscr | net_leverage | … |
|-------|-----------|------|--------------|---|
| CP‑1001 | 2025‑12‑31 | 1.80 | 2.95 | … |
| CP‑1001 | 2026‑03‑31 | 1.85 | 2.90 | … |

**Steps**

1. **Register the source** (MSSQL). In dev the "external" DB is the app's own H2 file (AUTO_SERVER),
   so it resolves end‑to‑end:
   ```jsonc
   // POST /api/v1/data-sources
   {
     "name": "Demo MSSQL Financials",
     "type": "MSSQL",
     "description": "Quarterly borrower financial metrics (columnar).",
     "connectionConfig": {
       "jdbcUrl": "jdbc:h2:file:./data/covenant;MODE=MSSQLServer;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE;AUTO_SERVER=TRUE",
       "username": "sa",
       "password": ""
     }
   }
   ```
2. **Register the table** (COLUMNAR, CP‑keyed):
   ```jsonc
   // POST /api/v1/data-sources/{dataSourceId}/tables
   {
     "name": "CP Quarterly Metrics",
     "tableName": "cp_metrics_demo",
     "entityKeyColumn": "cp_id",
     "asOfColumn": "as_of_date",
     "layout": "COLUMNAR",
     "entityKeyLevel": "CP_ID"
   }
   ```
3. **Register the measure** (`DSCR` → column `dscr`):
   ```jsonc
   // POST /api/v1/measures
   {
     "code": "DSCR",
     "name": "Debt Service Coverage Ratio",
     "dataSourceTableId": "{tableId}",
     "sourceColumn": "dscr",
     "defaultAggregation": "LATEST",
     "dataType": "NUMBER",
     "unit": "x"
   }
   ```
4. **Apply a covenant** whose formula is `DSCR >= 1.50` to a CP, then **assess**:
   ```
   POST /api/v1/credit-proposals/CP-1001/applied-covenants
   { "items": [ { "covenantMasterId": "{minimumDscrId}", "frequency": "QUARTERLY" } ] }

   POST /api/v1/assessments/run?appliedCovenantId={appliedId}
   ```
5. **Verify.** The result is `COMPLIANT` (latest DSCR 1.85 ≥ 1.50). Under the hood the resolver read
   `item_code = 'dscr'` for `entity_key = 'CP-1001'`, `LATEST` → the 2026‑03‑31 row.

---

### Example 2 — Intermediate: row / key‑value MSSQL

**Goal:** monitor ratios that live in an EAV table — `CURRENT_RATIO >= 1.20`,
`DEBT_EQUITY <= 2.00` — from the seeded *"Demo MSSQL Ratios (Row‑Level)"* source.

**The source data** (`cp_ratios_kv_demo`, one row per code):

| cp_id | as_of_date | metric_code | metric_value |
|-------|-----------|-------------|--------------|
| CP‑1001 | 2026‑03‑31 | CURRENT_RATIO | 1.85 |
| CP‑1001 | 2026‑03‑31 | DEBT_EQUITY | 1.10 |

**Steps**

1. **Register the source** (MSSQL) exactly as Example 1 (different name; `tableName` is irrelevant to
   the connection — the table entity carries it):
   ```jsonc
   { "name": "Demo MSSQL Ratios (Row-Level)", "type": "MSSQL",
     "connectionConfig": { "jdbcUrl": "jdbc:h2:file:./data/covenant;MODE=MSSQLServer;AUTO_SERVER=TRUE", "username": "sa", "password": "" } }
   ```
2. **Register the table** — choose **KEY_VALUE** and name the code + value columns:
   ```jsonc
   // POST /api/v1/data-sources/{dataSourceId}/tables
   {
     "name": "CP Ratios (Key-Value)",
     "tableName": "cp_ratios_kv_demo",
     "entityKeyColumn": "cp_id",
     "asOfColumn": "as_of_date",
     "layout": "KEY_VALUE",
     "codeColumn": "metric_code",
     "valueColumn": "metric_value",
     "entityKeyLevel": "CP_ID"
   }
   ```
   > Omitting `codeColumn`/`valueColumn` here → `400 INVALID_TABLE_LAYOUT`.
3. **Register measures by `sourceCode`** (no `sourceColumn` for KEY_VALUE):
   ```jsonc
   // POST /api/v1/measures   (repeat per ratio)
   { "code": "CURRENT_RATIO", "name": "Current Ratio", "dataSourceTableId": "{tableId}",
     "sourceCode": "CURRENT_RATIO", "defaultAggregation": "LATEST", "dataType": "NUMBER", "unit": "x" }
   ```
   In edit mode you can click **Preview distinct codes** (`GET …/tables/{tableId}/code-values`) to see
   the available codes (`CURRENT_RATIO`, `QUICK_RATIO`, `DEBT_EQUITY`, `DEBT_EBITDA`) before mapping.
4. **Apply & assess** covenants `CURRENT_RATIO >= 1.20` / `DEBT_EQUITY <= 2.00` as in Example 1.

**Why this shape?** Adding a new ratio needs **no schema change** — insert rows with a new
`metric_code` and register a measure. The resolver collapses the code filter into the same
`item_code = ?` predicate used for columnar, so evaluation is identical downstream.

---

### Example 3 — Complex: REST API source

**Goal:** read CP attributes (e.g. `FACILITY_AMOUNT`, `TENOR_MONTHS`) from an HTTP service instead of a
database. This is the seeded *"CP Attributes REST API"* source.

> **Runnable in dev.** The dev profile boots an embedded **WireMock** server on port `9311` that stubs the
> single `POST /api/data/values` endpoint (`wiremock/mappings/data-values.json`) with a full CP‑1001
> `DataSeriesResponse`, and `data-api.base-url` already points at it — so a REST covenant resolves out of
> the box with no external service. Both point‑in‑time (`from` omitted) and windowed (`from` present) reads
> hit the same stub ([§3.3](#33-multi-year-rest-series-us-ds-12)).

**Steps**

1. **Point the source at your data API.** Either set a per‑source `baseUrl` (routed absolutely, step 3)
   or leave it blank to use the global fallback:
   ```yaml
   covenant:
     clients:
       data-api:
         base-url: http://localhost:9311     # global fallback when a source sets no baseUrl
   ```
2. **Implement the contract** `POST {baseUrl}/api/data/values` (one endpoint; `from` omitted = latest):
   ```jsonc
   // Request from the app
   { "cpId": "CP-1001", "to": "2026-03-31", "keys": ["facilityAmount", "tenorMonths"] }
   // Response your service returns (a DataSeriesResponse)
   { "cpId": "CP-1001",
     "points": [ { "asOfDate": "2026-03-31", "values": { "facilityAmount": 62000, "tenorMonths": 84 } } ],
     "missingKeys": [], "sourceChangedAt": "2026-03-31T00:00:00Z" }
   ```
3. **Register the source** (REST). Set `baseUrl` (and optionally `valuesPath`) to route the fetch to your
   own service:
   ```jsonc
   // POST /api/v1/data-sources
   { "name": "CP Attributes REST API", "type": "REST",
     "description": "Credit-proposal attribute service.",
     "connectionConfig": { "baseUrl": "http://localhost:9311", "valuesPath": "/api/data/values" } }
   ```
   > With a `baseUrl` the fetch targets `{baseUrl}{valuesPath}` absolutely; omit it to fall back to the
   > global `data-api.base-url` from step 1.
4. **Register the table** (COLUMNAR; `tableName`/columns are nominal for REST — the `keys` sent are the
   measures' source values):
   ```jsonc
   { "name": "CP Attributes (REST)", "tableName": "attributes",
     "entityKeyColumn": "cpId", "asOfColumn": "asOfDate", "layout": "COLUMNAR", "entityKeyLevel": "CP_ID" }
   ```
5. **Register measures** whose `sourceColumn` is the JSON key your API returns:
   ```jsonc
   { "code": "FACILITY_AMOUNT", "name": "Facility Amount", "dataSourceTableId": "{tableId}",
     "sourceColumn": "facilityAmount", "defaultAggregation": "CURRENT", "dataType": "NUMBER", "unit": "INR cr" }
   ```
6. **Assess.** On each run the app fetches the snapshot, lands it in staging, and evaluates — the exact
   same resolve path as MSSQL. In dev the WireMock stub above answers automatically; against a real
   deployment, if you do **not** implement the endpoint, keep such covenants **manual**: apply them and
   complete via `POST /api/v1/assessments/{id}/complete-manual`.

---

### Example 4 — Very complex: obligor‑keyed + windowed aggregation

**Goal:** a group‑level warehouse delivers data at **obligor** grain, and the covenant is a
**period‑aware rolling average**: `AVG(OBL_DSCR, '4q', PERIODIC) >= 1.50`. Three CPs share one obligor
(Reliance = `OBL‑RIL` → CP‑1001/1002/1003) and each must assess from the shared data.

> **Seeded for you (dev).** `db/demo/V20__ingestion_demo_data.sql` already onboards everything on the
> *data* side: the external `obligor_metrics_demo` table (obligor‑grain `group_dscr` history), the
> registered source **"Demo Group Financials (Obligor)"** + its obligor‑keyed table + the **`OBL_DSCR`**
> measure, and the `cov_obligor_cp_map` rows (CP‑1001/1002/1003 → `OBL‑RIL`). So in dev you skip
> registration and jump straight to authoring + applying the covenant.

**Concepts in play**

- **Obligor mapping** (`cov_obligor_cp_map`, one obligor → many CPs, unique on `cp_id`) is captured at
  CP lookup: `GET /api/v1/credit-proposals/{cpId}` records the CP→obligor link and denormalises
  `obligor_id` onto the CP cache. No obligor id in the upstream payload ⇒ no mapping row (harmless). In
  dev the seed pre‑captures the Reliance/Tata mappings so no live upstream is needed.
- An **OBLIGOR_ID** table resolves the CP's obligor once per resolve, binds it as the `entity_key`,
  reads the obligor's staging rows, and **fans out** to every CP under that obligor. No mapping ⇒ the
  term is cleanly *not present* (never a wrong‑CP read).
- **PERIODIC** sampling first reduces each quarter to its period‑end DSCR, then `AVG`s those.

**Steps** *(dev — using the seed)*

1. **(Optional) Confirm the obligor.** `GET /api/v1/credit-proposals/CP-1001` — the onboarding wizard
   shows an **Obligor: OBL‑RIL** line on the lookup card (it reads the seeded mapping/cache).
2. **Author the windowed covenant** `AVG(OBL_DSCR, '4q', PERIODIC) >= 1.50` in the Covenant Library
   (it references the seeded `OBL_DSCR` measure), then apply it to all three CPs:
   ```
   POST /api/v1/credit-proposals/CP-1001/applied-covenants
   { "items": [ { "covenantMasterId": "{id}", "frequency": "QUARTERLY" } ] }
   POST /api/v1/credit-proposals/CP-1002/applied-covenants   { "items": [ … same id … ] }
   POST /api/v1/credit-proposals/CP-1003/applied-covenants   { "items": [ … same id … ] }
   ```
3. **Assess all three.** Each run resolves `OBL-RIL`, reads the *shared* obligor staging rows, collapses
   each of the last 4 quarters to its period‑end `group_dscr`, averages them (≈ 1.85 ≥ 1.50 →
   `COMPLIANT`), and attaches the result to its own CP + snapshot.

**To register it yourself (prod / UI)** instead of using the seed: register an MSSQL source (Example 1),
then a table at obligor grain — `entityKeyColumn` is the obligor id column and `entityKeyLevel` is
`OBLIGOR_ID`:
```jsonc
{ "name": "Group Financials (Obligor)", "tableName": "obligor_metrics_demo",
  "entityKeyColumn": "obligor_id", "asOfColumn": "as_of_date",
  "layout": "COLUMNAR", "entityKeyLevel": "OBLIGOR_ID" }
```
…then a measure `OBL_DSCR` (`sourceColumn: "group_dscr"`, `dataType: NUMBER`) on it.

**What to check:** a single set of `cov_staging_value` rows under `entity_key = 'OBL-RIL'` feeds all
three CPs' assessments; a CP with no mapping row yields a *not present* term rather than an error.

---

### Example 5 — The freshness gate in action (assess twice)

**Goal:** see US‑DS‑13's freshness gate skip a redundant fetch. Uses the seeded Example 1 DSCR covenant
(`DSCR >= 1.50` on CP‑1001) — no new registration.

**Steps**

1. **First assessment.** `POST /api/v1/assessments/run?appliedCovenantId={dscrAppliedId}`.
   The gate finds **no** staged copy for `(CP Quarterly Metrics, CP-1001)` → reason **missing** → it
   ingests. Confirm `cov_staging_value` now holds `item_code = 'dscr'` rows for `entity_key = 'CP-1001'`,
   and `cov_ingestion_state` has one row with `last_status = ok` and a `staged_as_of` of the as‑of date.
2. **Second assessment, same day.** Run the same covenant again.
   The gate finds `staged_as_of >= required_as_of`, no newer `source_changed_at`, TTL not elapsed →
   reason **fresh** → it **skips** (no JDBC call). `cov_ingestion_state.last_status` flips to `skipped`;
   staging rows are unchanged (same `ingest_batch_id`). The assessment still completes from the local copy.
3. **Force a re‑pull (optional).** Simulate a restatement by making the gate see the source as changed —
   e.g. bump a `group_dscr`/`dscr` value in the external demo table and either wait for the TTL
   (`covenant.ingestion.ttl-hours`, default 24h) or trigger `forceRefresh` — the next run ingests again
   (reason **ttl** or **source‑changed**) and overwrites the affected dates idempotently.

**What to check:** exactly one `cov_ingestion_state` row per `(table, entity)`; `last_status` cycles
`ok → skipped`; no duplicate staging rows across re‑runs (`uk_staging_value` holds).

---

### Example 6 — Multi‑year windowed aggregation (first‑run history)

**Goal:** prove a multi‑year window resolves on the **first** assessment because the MSSQL ingest copies
the whole `[from, as‑of]` window in one shot. Uses the deeper CP‑1001 history seeded by V20 (nine
quarters, 2024‑03‑31 → 2026‑03‑31).

**Steps**

1. **Author** `AVG(DSCR, '4q', PERIODIC) >= 1.50` (reuses the seeded `DSCR` measure / `cp_metrics_demo`)
   and apply it to **CP‑1001**.
2. **Assess once.** The gate ingests CP‑1001's `[from, as‑of]` window — so every quarter‑end DSCR in the
   seeded series (rising ~1.70 → ~1.80 across 2024–2025, plus the V10 2025‑12‑31/2026‑03‑31 rows) is
   already staged. `PERIODIC` reduces each quarter to its period‑end value, `AVG`s the four most recent,
   and compares to 1.50 → `COMPLIANT` on the **first** run (no waiting for history to accrue). A same‑day
   re‑run copies nothing (the watermark holds); a covenant with a *wider* look‑back triggers a reach‑back
   full re‑fetch ([§3](#3-how-data-flows-at-assessment-time)).
3. **Contrast with REST.** A REST source resolves the same window by sending `from` on the single
   `POST {baseUrl}/api/data/values` endpoint ([§3.3](#33-multi-year-rest-series-us-ds-12)) so it likewise
   resolves on the first run; steady‑state runs then fetch only newer dates via the shared incremental
   cursor.

**What to check:** `cov_staging_value` for `(cp_metrics_demo table, CP-1001, item_code='dscr')` holds
multiple quarterly rows after the first assessment; the aggregated value in `data_snapshot_json` matches
the average of the last four quarter‑end DSCRs.

---

### Example 7 — REST obligor‑keyed, mixed annual/quarterly series

**Goal:** the hardest real‑world shape — an **external REST API** publishes **obligor‑grain** financial
ratios for **many dates**, where the group files an **annual** (fiscal‑year‑end `03‑31`) number every
year and **sometimes also quarterly** (`06‑30`/`09‑30`/`12‑31`). A windowed covenant
`AVG(GRP_DSCR, '3y', PERIODIC) >= 1.30` therefore drives a **multi‑year series fetch** (US‑DS‑12), lands
the whole history in staging on the first assessment, and **fans one obligor's data out to two CPs**
(US‑DS‑11). This is Example 3 (REST) + Example 4 (obligor fan‑out) + Example 6 (multi‑year window) in one
journey.

> **Runnable in dev — no external service.** `db/demo/V21__rest_obligor_demo_data.sql` seeds a brand‑new
> obligor group **Adani (`OBL‑ADANI`)** with two CPs — **CP‑2001** (Adani Ports & SEZ, INFRASTRUCTURE/
> PORTS) and **CP‑2002** (Adani Green Energy, POWER/RENEWABLES) — the REST source + obligor‑keyed table +
> three measures (`GRP_DSCR`, `GRP_NET_LEVERAGE`, `GRP_INTEREST_COVER`), and the `cov_obligor_cp_map`
> rows. The source's `connectionConfig.baseUrl` is `http://localhost:9311` and is **used** to route the
> fetch. The dev **WireMock** server (port `9311`) answers the actual data:
> - `POST /api/data/values` → `wiremock/mappings/demo-obligor-values-adani.json` (matched on
>   `cpId == "OBL-ADANI"`, priority 1) returns **11 dated points** as one `DataSeriesResponse`:
>   annual‑only for FY2022/FY2023, then annual **and** quarterly for FY2024→FY2026. This one endpoint
>   serves both point‑in‑time and windowed reads (no separate `/series` mapping).
> - `GET /api/credit-proposals/CP-2001|CP-2002` → `demo-cp-2001.json`/`demo-cp-2002.json`, each carrying
>   `"obligorId": "OBL-ADANI"` so a live lookup re‑captures the mapping.
>
> So in dev you skip registration and jump straight to authoring + applying the covenant.

**Concepts in play**

- **Obligor keying over REST.** The table's `entityKeyLevel = OBLIGOR_ID`, so at resolve the app maps the
  CP to its obligor (`OBL‑ADANI`) and sends **that** as the request `cpId` (the field name is literal;
  it carries whichever entity key the table declares). Both CPs resolve to the same obligor and read the
  **same** staged rows.
- **Mixed frequency + PERIODIC.** The `'3y'` window is *yearly*; `PERIODIC` first reduces each fiscal year
  to its **period‑end** (`03‑31`) DSCR, so the intra‑year quarterly points are collapsed, not
  double‑counted. Only the three fiscal‑year‑ends in range — `2024‑03‑31` (1.55), `2025‑03‑31` (1.53),
  `2026‑03‑31` (1.50) — are averaged: `(1.55 + 1.53 + 1.50) / 3 ≈ 1.527 ≥ 1.30` → **COMPLIANT**.
- **Windowed vs point‑in‑time.** Because the window start is strictly before the as‑of date, ingestion
  sends `from` on the single `POST /api/data/values` endpoint and lands the full `[from, to]` history on
  the first run; steady‑state runs fetch only dates newer than the staged watermark (shared incremental
  cursor, [§3.3](#33-multi-year-rest-series-us-ds-12)).

**Steps** *(dev — using the seed)*

1. **(Optional) Confirm the obligor.** `GET /api/v1/credit-proposals/CP-2001` — the lookup card shows an
   **Obligor: OBL‑ADANI** line (from the seeded mapping/cache; a live call re‑captures it from the stub).
2. **Author the windowed covenant** `AVG(GRP_DSCR, '3y', PERIODIC) >= 1.30` in the Covenant Library
   (it references the seeded `GRP_DSCR` measure), then apply it to **both** CPs:
   ```
   POST /api/v1/credit-proposals/CP-2001/applied-covenants
   { "items": [ { "covenantMasterId": "{id}", "frequency": "YEARLY" } ] }
   POST /api/v1/credit-proposals/CP-2002/applied-covenants   { "items": [ … same id … ] }
   ```
3. **Assess both.** `POST /api/v1/assessments/run?appliedCovenantId={…}` for each. The **first** run for
   the obligor pulls the whole series into `cov_staging_value` under `entity_key = 'OBL-ADANI'`; both CPs'
   assessments read those shared rows, collapse each fiscal year to its `03‑31` DSCR, average the last
   three, and return **COMPLIANT** (≈ 1.527) — each result attached to its own CP + `data_snapshot_json`.

**To register it yourself (prod / UI)** instead of using the seed:
```jsonc
// 1) REST source — baseUrl routes the fetch to this service (absolute {baseUrl}/api/data/values)
{ "name": "Group Financials API (Obligor, REST)", "type": "REST",
  "description": "External REST feed of group/obligor-grain ratios (annual + quarterly).",
  "connectionConfig": { "baseUrl": "http://localhost:9311" } }
// 2) Obligor-keyed COLUMNAR table (tableName/columns are nominal for REST; the keys sent are the
//    measures' sourceColumn values, which must equal the JSON keys in each series point's "values")
{ "name": "Group Financials API (Obligor, REST)", "tableName": "obligor_financials_api",
  "entityKeyColumn": "obligorId", "asOfColumn": "asOfDate",
  "layout": "COLUMNAR", "entityKeyLevel": "OBLIGOR_ID" }
// 3) Measure whose sourceColumn is the JSON key the series returns
{ "code": "GRP_DSCR", "name": "Group DSCR", "dataSourceTableId": "{tableId}",
  "sourceColumn": "group_dscr", "defaultAggregation": "LATEST", "dataType": "NUMBER", "unit": "x" }
```
…then set the source's `baseUrl` (or the global `covenant.clients.data-api.base-url`) to a service
implementing the single `POST /api/data/values` endpoint per [§4.2](#42-rest-sources).

**What to check:** a single set of `cov_staging_value` rows under `entity_key = 'OBL-ADANI'` (11 dates ×
3 measures on the first run) feeds **both** CP‑2001 and CP‑2002; the aggregated value in each
`data_snapshot_json` is the average of the three fiscal‑year‑end `group_dscr` values, not of all eleven
points; a re‑run the same day fetches nothing new (watermark at `2026‑03‑31`).

---

### Example 8 — Obligor‑level REST feed with many data points (full frontend walkthrough)

**Goal:** stand up a brand‑new **external REST API** that publishes **obligor‑grain** financials —
several data points (`DSCR`, `PAT`, `EBITDA`, `CURRENT_RATIO`, …) for **many financial dates** — then map
each data point to a **measure**, author **covenants** over them, and assess two CPs that share the
obligor. Unlike Examples 3–7 (which lean on the dev seed), this one is done **entirely from the frontend**,
so you can reproduce it against your own service with no SQL. Open the app at
**http://localhost:9310**.

> **What you provide.** A service that answers the single as‑of‑aware contract
> `POST {baseUrl}/api/data/values` ([§4.2](#42-rest-sources)) and returns a `DataSeriesResponse` whose each
> point carries the data‑point keys in its `values` map. For this walkthrough assume an obligor
> `OBL‑NWIND` (borrower *"Northwind Infrastructure"*) with two credit proposals **CP‑3001** and **CP‑3002**,
> and that the service returns, for `cpId = "OBL-NWIND"`:
>
> ```jsonc
> // Response to POST {baseUrl}/api/data/values   (from present → the [from, to] series)
> { "cpId": "OBL-NWIND",
>   "points": [
>     { "asOfDate": "2024-03-31", "values": { "dscr": 1.62, "pat": 420, "ebitda":  980, "currentRatio": 1.45 } },
>     { "asOfDate": "2025-03-31", "values": { "dscr": 1.58, "pat": 465, "ebitda": 1050, "currentRatio": 1.50 } },
>     { "asOfDate": "2026-03-31", "values": { "dscr": 1.55, "pat": 510, "ebitda": 1120, "currentRatio": 1.48 } }
>   ],
>   "missingKeys": [],
>   "sourceChangedAt": "2026-03-31T00:00:00Z" }
> ```
>
> The JSON keys (`dscr`, `pat`, `ebitda`, `currentRatio`) are exactly what you type as each measure's
> **Source column** in step 3 — that binding is the whole trick.

**Step 1 — Register the REST source.** Left nav → **Data Sources** → **Register data source**
(opens the *Register data source* form).
- **Name:** `Northwind Group Financials API`
- **Type:** choose **`REST API`**.
- **Base URL:** the host root of your service, e.g. `http://localhost:9311` — the app appends the values
  path itself, calling `{Base URL}/api/data/values`. (The form captures the base URL only; the path
  defaults to `/api/data/values`.)
- Click **Register**. If your service isn't reachable the source still saves — its covenants simply stay
  **manual‑only** until the endpoint answers.

**Step 2 — Register the obligor‑keyed table.** Open the source → **Add table** (page *Register table*).
- **Name:** `Group Financials (Obligor, REST)`
- **Table name:** `group_financials_api` (nominal for REST — no JDBC runs; it's just a label).
- **Entity key column:** `obligorId`  ·  **As‑of column:** `asOfDate`
  (both are nominal labels for REST; they mirror the response field names.)
- **Entity key level:** switch to **`Obligor`** — this is what makes one obligor's data fan out to *every*
  CP under it.
- **Mapping shape:** leave **`Columnar`** (each data‑point key is its own measure).
- Click **Register table**.

**Step 3 — Create one measure per data point.** Left nav → **Measure Library** → **Create measure**.
Repeat for each data point, selecting your **Data source** and **Table** from step 1–2, and typing the
**Source column** to match the JSON key exactly:

| Code | Name | Source column | Data type | Default aggregation | Unit |
|------|------|---------------|-----------|---------------------|------|
| `DSCR` | Debt Service Coverage Ratio | `dscr` | `NUMBER` | `AVG` | `x` |
| `PAT` | Profit After Tax | `pat` | `NUMBER` | `LATEST` | `INR cr` |
| `EBITDA` | EBITDA | `ebitda` | `NUMBER` | `LATEST` | `INR cr` |
| `CURRENT_RATIO` | Current Ratio | `currentRatio` | `NUMBER` | `LATEST` | `x` |

Click **Create measure** for each. (Choosing `AVG` as `DSCR`'s **Default aggregation** simply pre‑fills the
aggregation when it's used bare; a formula can always override it, as below.)

**Step 4 — Author the covenants.** Left nav → **Covenant Library** → **Create covenant**. For each, set a
**Name** and **Description**, pick a **Default monitoring frequency**, leave **Assessment type** on
**`Auto`**, and enter the **Covenant condition** in the formula builder:

- `AVG(DSCR, '3y', PERIODIC) >= 1.30` — averages the three fiscal‑year‑end DSCRs (`PERIODIC` collapses each
  year to its `03‑31` figure).
- `CURRENT_RATIO >= 1.20` — the latest current ratio.
- `PAT >= 300` — latest profit after tax (INR cr).

Click **Create covenant** for each. (Use **Formula Builder** in the left nav first if you want to validate
an expression interactively before saving.)

**Step 5 — Apply to the CPs.** Left nav → **CP Onboarding**.
1. **Lookup** `CP-3001` → the card shows an **Obligor: OBL‑NWIND** line (captured from the CP lookup, i.e.
   your CP service returns its `obligorId`). This mapping is what lets the obligor‑grain feed resolve for
   this CP; without it the term is cleanly *not present* rather than a wrong‑CP read.
2. In **Review & Configure**, add the three covenants (**Add from library**), set each **Cadence & start
   date**, then **Confirm and apply**.
3. Repeat the lookup + apply for `CP-3002` (same obligor → same shared data).

**Step 6 — Assess & read the result.** Left nav → **Monitoring** → open `CP-3001`.
- Click **Run now** on a covenant (or assess the CP). The **first** run for the obligor pulls the whole
  `[from, to]` series once into staging under `entity_key = 'OBL-NWIND'`; both CPs then read those shared
  rows.
- The assessment table shows each covenant's **Expression**, **Actual**, **Headroom**, and a **Status**
  badge (`COMPLIANT` / `BREACHED`). Here `AVG(DSCR,'3y',PERIODIC) = (1.62+1.58+1.55)/3 ≈ 1.58 ≥ 1.30` →
  `COMPLIANT`; `CURRENT_RATIO = 1.48 ≥ 1.20` → `COMPLIANT`; `PAT = 510 ≥ 300` → `COMPLIANT`.
- On any breach, use **Set disposition** to record a `WAIVER`/`EXTENSION`.

**What to check:** one set of `cov_staging_value` rows under `entity_key = 'OBL-NWIND'` (each financial
date × four data points) feeds **both** CP‑3001 and CP‑3002; a same‑day re‑run fetches nothing new (the
watermark holds); adding a *fifth* data point later needs only a new **measure** (Source column = the new
JSON key) — no source or table change. If the endpoint is unreachable you'll see `502 DATA_API_FAILED`
([§10](#10-validation-rules--troubleshooting)) and can keep those covenants manual until it's live.

---

## 10. Validation rules & troubleshooting

| Symptom / error | Cause | Fix |
|-----------------|-------|-----|
| `400 INVALID_TABLE_LAYOUT` | KEY_VALUE without `codeColumn`+`valueColumn`, or COLUMNAR with them. | Match layout to fields (Section 5). |
| `400 DATA_SOURCE_CONFIG_MISSING` | MSSQL source has no `jdbcUrl`. | Add `jdbcUrl` to `connectionConfig`. |
| `400` "must be a valid SQL identifier" | Table/column name breaks `^[A-Za-z_][A-Za-z0-9_]*$`. | Use letters/digits/underscore; don't start with a digit; no spaces/dots (schema is a separate field). |
| `502 DATA_API_EMPTY` / `DATA_API_FAILED` | REST data API unreachable or returned empty/error. | Verify the source's `baseUrl` (or the global `data-api.base-url` fallback) and that `POST {baseUrl}/api/data/values` works; or keep the covenant manual. |
| Assessment `ERROR: Missing values for measures [X]` | No staging row for that measure/entity/date. | Confirm the source has data for the CP/obligor & as‑of; check `sourceColumn`/`sourceCode` matches storage. A *stale‑fresh* control row can no longer cause this — the gate re‑ingests with reason `staging-empty` when nothing is staged ([§3.1](#31-the-freshness-gate)). |
| Column dropdowns empty in the form | Discovery only works for a **persisted** table. | Use text inputs in create mode; edit the table to get searchable dropdowns. |
| KEY_VALUE measure shows blank in Measure Library | Row measures have no `sourceColumn`. | Expected — the library renders `table.<sourceCode>` for KEY_VALUE. |
| Obligor line missing on lookup card | Upstream returned no `obligorId`. | Only obligor‑keyed tables need it; CP‑keyed tables are unaffected. |
| Assessment logs `SKIPPED` ingestion / staging rows unchanged on re‑run | The freshness gate found the staged copy already covers the required as‑of within TTL. | **Expected** — the copy is reused, not re‑pulled. To force a fresh pull, let the TTL (`ttl-hours`, default 24h) lapse, register a newer as‑of, or call the internal `forceRefresh`/`recordSourceChange` service hook. See [3.1](#31-the-freshness-gate). |
| First assessment ingests, second (same day) does nothing | Freshness gate decision `fresh` → `SKIPPED`; `cov_ingestion_state.next_eligible_at` is in the future. | Working as designed — assess twice to observe the gate ([Example 5](#example-5--the-freshness-gate-in-action-assess-twice)). |
| Windowed aggregation (e.g. `AVG(DSCR,'4q',PERIODIC)`) resolves on the **first** assessment | Both source types copy the whole `[from, as‑of]` window on the first run, then only newer dates. | Expected — no need to assess repeatedly to accumulate history ([Example 6](#example-6--multi-year-windowed-aggregation-first-run-history)). |
| Obligor‑keyed measure returns `present=false` for a CP | No `cov_obligor_cp_map` row for that CP (obligor unknown). | Look the CP up first (captures the mapping), or seed/backfill the map. Missing mapping yields a clean *not‑present*, never a wrong‑obligor read. |
| `cov_ingestion_state.last_status = FAILED` for one table | That source's ingest threw; the pre‑stage sweep isolates per‑target failures. | Other targets still stage; fix the source and the next due sweep (or assessment) retries. |

**Backward compatibility:** `layout` defaults to `COLUMNAR` and `entityKeyLevel` to `CP_ID`, so existing
CP‑keyed columnar tables behave exactly as before with no changes.

---

## 11. REST endpoint quick reference

**Data sources** — base `/api/v1/data-sources`

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/` | Create (→ 201 + `Location`). |
| GET | `/?activeOnly=true` | List. |
| GET | `/{id}` | Get one. |
| PUT | `/{id}` | Update. |
| DELETE | `/{id}` | Soft‑deactivate. |
| GET | `/{id}/columns` | Discover columns (persisted MSSQL source). |
| POST | `/{id}/test` | Test connection. |

**Tables** — base `/api/v1/data-sources/{dataSourceId}/tables`

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/` | Register (→ 201). |
| GET | `/` | List tables under the source. |
| GET | `/{tableId}` | Get one. |
| PUT | `/{tableId}` | Update. |
| DELETE | `/{tableId}` | Deactivate. |
| GET | `/{tableId}/columns` | Discover columns. |
| GET | `/{tableId}/code-values` | Distinct codes (KEY_VALUE preview). |

**Measures** — `/api/v1/measures` : `POST` · `GET` (list) · `GET /{id}` · `PUT /{id}` · `DELETE /{id}`

**Apply & assess**

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/v1/credit-proposals/{cpId}` | Look up a CP (captures obligor mapping). |
| POST | `/api/v1/credit-proposals/{cpId}/applied-covenants` | Apply covenant(s): `{ "items": [ … ] }`. |
| POST | `/api/v1/assessments/run?appliedCovenantId={id}` | Run one assessment now (JIT‑ingests through the freshness gate first). |
| POST | `/api/v1/assessments/{id}/complete-manual` | Complete a `PENDING_REVIEW` manual assessment. |
| GET | `/api/v1/assessments?cpId={cpId}` | List a CP's assessments (newest first). |

**Data API (upstream, consumed by the ingestion layer)** — not part of this service; this is the single
endpoint the configured REST source must expose at its own `baseUrl`. See [4.2](#42-rest-sources) and
[3.3](#33-multi-year-rest-series-us-ds-12).

| Method | Path | Purpose |
|--------|------|---------|
| POST | `{baseUrl}/api/data/values` | As‑of‑aware fetch. `from` omitted → latest point `<= to`; `from` present → the `[from, to]` series. Returns a `DataSeriesResponse` (`points[]` + optional `sourceChangedAt`). |

> **Ingestion has no public endpoint.** Staging a durable copy is driven internally — just‑in‑time on
> `assessments/run`, and ahead of need by the scheduled **pre‑stage sweep** (`ingestion-pre-stage-run`,
> cron `covenant.ingestion.pre-stage-cron`, default `0 30 5 * * ?`). `forceRefresh(...)` and
> `recordSourceChange(...)` exist only as internal service hooks (no REST route). See
> [3.2](#32-due-driven-pre-stage-sweep) and [3.4](#34-ingestion-configuration-knobs).

---

*Enum values persist lowercase in the database via CHECK constraints; shown UPPERCASE here and in the
REST API for readability.*
