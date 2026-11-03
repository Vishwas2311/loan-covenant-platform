# US-DS-12 — Multi-year REST series fetch (obligor/CP history for windowed aggregation)

**Epic:** Data source & measure modeling
**Status:** Draft · **Priority:** Medium
**Depends on:** US-DS-11 (durable `cov_staging_value` copy + unified resolver + REST ingestion)

## Story
**As a** Covenant Administrator / Integrator onboarding a **REST** data source
**I want** the system to pull **multiple financial years** of an entity's data from the source API in
one ingest (e.g. the last 5 years of an obligor's financials) rather than a single as-of snapshot
**so that** multi-year windowed aggregations (e.g. a 5-year AVG, a `PERIODIC` year-end series) can be
assessed correctly on the **first** run — matching the full-history behaviour MSSQL sources already
get — instead of only accruing one date per assessment over time.

## Description
US-DS-11 lands a durable copy of source data into `cov_staging_value` and makes REST sources
assessable. But the two source types behave differently on history depth:

- **MSSQL ingest copies full history.** `StagingIngestionService` reads
  `WHERE <keyColumn> = ? AND <asOfColumn> <= ?`, so every historical row at/before the as-of date is
  copied on the first ingest. A 5-year window resolves immediately.
- **REST ingest is a single-date snapshot.** `DataApiClient.fetch(entityKey, asOf, keys)` returns one
  `DataValuesResponse` for one date. So a multi-year window over a REST source only fills in **one
  as-of date per assessment**; the history accrues slowly over successive runs and a fresh covenant
  cannot evaluate a multi-year window on day one.

This story closes that gap: add a **series** fetch to the REST client that returns many dated points
in one call, derive the date range **automatically from the covenant's aggregation window**, land one
staging row-set per dated point, and fetch **incrementally** so we don't re-pull history we already
have. The point-in-time `fetch(...)` stays for LATEST/CURRENT.

### Range = auto from the covenant window
The `from` date is derived from the **widest aggregation window** across the measures being resolved
(e.g. a `AVG` over a 5-year `PERIODIC` window ⇒ `from = asOf − 5 years`, aligned to the fiscal-year
boundary the resolver already computes). `to = asOf`. When no windowing applies (a plain
LATEST/CURRENT measure), the engine keeps using the single-date `fetch(...)` — no series call.

### Incremental fetch
On each ingest the service reads the **max `as_of_date` already in `cov_staging_value`** for that
`(data_source_table_id, entity_key)` and only requests dates **newer than** it (`from =
max(existingMaxAsOf + 1 day, windowStart)`). The **full window** is pulled only on the **first** run
(no existing rows) or **on demand** (explicit re-ingest). This keeps steady-state calls small while
still back-filling history once.

### Landing the series
`readFromRest` iterates the returned `DataPoint`s and lands one staging row-set **per dated point**.
The existing set-based idempotent delete
(`deleteByDataSourceTableIdAndEntityKeyAndAsOfDateIn(tableId, entityKey, dates)`) already supports
overwriting **multiple** dates in one transaction, so re-ingesting an overlapping range is safe and
non-duplicating.

### Works with obligor grain (US-DS-11)
For an obligor-keyed table the series is fetched at the resolved **obligor** grain and shared across
the obligor's CPs, exactly as the single-snapshot path does — the only change is depth (many dates
vs one).

## Acceptance criteria

### AC1 — Series fetch API
```
Given the REST data client
Then it exposes fetchSeries(entityKey, from, to, keys) returning a DataSeriesResponse of dated points
And the existing point-in-time fetch(entityKey, asOf, keys) is retained unchanged for LATEST/CURRENT.
```

### AC2 — Range auto-derived from the covenant window
```
Given an assessment whose measures include a windowed aggregation (e.g. 5-year AVG / PERIODIC)
When REST ingestion runs
Then from is computed from the widest window across those measures (fiscal-boundary aligned) and to = asOf
And a plain LATEST/CURRENT measure with no window still uses single-date fetch(...) (no series call).
```

### AC3 — Multi-year window resolves on the first run
```
Given a fresh REST source and a covenant with a 5-year window
When it is assessed for the first time
Then multiple years of dated points are ingested into cov_staging_value in one ingest
And the aggregation evaluates over the full window immediately (parity with an MSSQL source).
```

### AC4 — Incremental fetch in steady state
```
Given cov_staging_value already holds history for (table, entity)
When ingestion runs again
Then only dates newer than the existing max as_of_date are requested (from = max(existingMax+1, windowStart))
And the full window is re-pulled only on the first run (no existing rows) or an explicit re-ingest.
```

### AC5 — Idempotent multi-date landing
```
Given a series whose dates overlap rows already in staging
When it is landed
Then existing rows for those (data_source_table_id, entity_key, as_of_date) sets are replaced, not duplicated
And the unique key (data_source_table_id, entity_key, as_of_date, item_code) is never violated.
```

### AC6 — Missing keys / empty series handled
```
Given the API returns missingKeys or an empty point list for part of the range
Then absent measures resolve to present = false (same NA handling as US-DS-11)
And an empty series is not an error — the assessment proceeds with whatever is present.
```

### AC7 — Obligor-grain series (US-DS-11 interplay)
```
Given an obligor-keyed table
When a multi-year series is fetched
Then it is fetched once at the resolved obligor grain and shared across the obligor's CPs
And each CP's assessment reads the shared obligor history.
```

### AC8 — Resilience unchanged
```
Given the REST call
Then fetchSeries uses the same timeout / circuit-breaker posture as fetch(...)
And a failed series call surfaces the same way as today's failed fetch (no partial-commit of staging rows).
```

## Data / API notes
- **New domain records** (in `backend/.../domain`, coverage-excluded like other DTOs):
  - `DataPoint(LocalDate asOfDate, Map<String,Object> values)`
  - `DataSeriesResponse(String cpId, List<DataPoint> points, List<String> missingKeys)`
- **`DataApiClient`** — add `DataSeriesResponse fetchSeries(String entityKey, LocalDate from,
  LocalDate to, List<String> keys)`; keep `DataValuesResponse fetch(...)`.
- **`WebClientDataApiClient`** — implement `fetchSeries` (e.g. `POST /api/data/values/series` with body
  `{ cpId|entityKey, from, to, keys }`) reusing the existing timeout + circuit-breaker config.
- **`StagingIngestionService`** — `ingest(...)` signature grows a range (`from`/`to`) or derives it
  internally; branch: REST windowed ⇒ `fetchSeries` (incremental via max-as-of lookup) then land one
  row-set per `DataPoint`; REST point-in-time ⇒ existing `fetch`; MSSQL ⇒ unchanged full-history copy.
  Needs a `StagingValueRepository` "max as_of_date for (table, entity)" query for the incremental
  cursor.
- **`MeasureAggregationResolver` / `AssessmentService`** — compute the widest window across the
  request's measures (reuse the resolver's existing fiscal-aligned period math) and pass the derived
  `from` through `ingest(...)`; JIT ingestion still runs once per `(table, entity, asOf)`.
- **No schema change** — `cov_staging_value` already keys on `as_of_date`; more dates = more rows.
- **Tests** — WireMock stub returning a multi-point series; `StagingIngestionServiceTest` for
  first-run full window, incremental top-up (only newer dates requested), idempotent overlapping
  re-ingest, empty/missing-keys; a resolver/assessment test proving a 5-year window resolves on first
  run; a `WebClientDataApiClientTest` for `fetchSeries` success + failure/circuit-breaker.

## Interaction with other features
- **US-DS-11:** direct extension — reuses `cov_staging_value`, the idempotent multi-date delete, the
  REST ingestion path, and obligor fan-out; only adds depth to REST history.
- **US-AGG-11 (period-aware windowed aggregation):** the derived `from` follows the same
  fiscal-year-aligned period boundaries the resolver already computes; PERIODIC year-end series now
  have real history to sample.
- **US-036 (immutable snapshot):** unchanged — each assessment's `data_snapshot_json` still captures
  exactly what that run evaluated.

## Dependencies
- **US-DS-11** must be delivered first (staging store, REST ingestion, obligor mapping).
- Upstream Data API must expose a **range/series** endpoint returning multiple dated points; absent
  that, this story cannot proceed and REST sources keep accruing one date per assessment.

## Open questions (confirm with product / upstream)
- **Series endpoint contract** — exact path/params and whether the upstream paginates a long range
  (may need chunked `fetchSeries` calls for very wide windows).
- **Max window guard** — cap the auto-derived range (e.g. refuse > N years) to bound API cost.
- **On-demand full re-ingest** — where the "force full re-pull" trigger lives (admin action vs a
  table flag) — the incremental default covers steady state.

## Out of scope
- A scheduled / bulk backfill pipeline (ingestion stays JIT at assessment time).
- Changing MSSQL ingestion (already full-history).
- Multi-date support for the point-in-time `fetch(...)` path (LATEST/CURRENT stays single-date).
