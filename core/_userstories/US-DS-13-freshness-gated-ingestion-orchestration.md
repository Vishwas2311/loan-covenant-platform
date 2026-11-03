# US-DS-13 — Freshness-gated, due-driven ingestion orchestration

**Epic:** Data source & measure modeling
**Status:** Draft · **Priority:** High
**Depends on:** US-DS-11 (durable `cov_staging_value` copy, obligor→CP mapping, JIT ingestion),
US-DS-12 (multi-year REST series fetch — incremental cursor)

## Story
**As a** platform operator / data warehouse architect running covenant monitoring at scale
(≈ 5,000 CPs annually, ≈ 500 covenants each)
**I want** the system to ingest source data **once per entity, ahead of need, only when the staged
copy is missing or stale** — instead of fetching per covenant execution or doing a blind daily full
pull
**so that** covenant evaluation is a pure local read, the source is touched a few dozen times a night
instead of hundreds of thousands, source outages are isolated from business evaluation, and every
assessment reads a consistent point-in-time cut.

## Description
Today a data source is called **whenever a covenant is evaluated** (scheduler or manual run), which
in turn issues the API/DB call per assessment. At the benchmark scale (5,000 CPs × 500 covenants =
~2.5M evaluations/cycle) this couples the *business-rule count* to the *source-load count* and does
not survive: massive redundant fetches (≈ 500 covenants on one CP re-pull the same entity data),
N+1/connection exhaustion on DB sources, rate-limit/circuit-breaker failures on REST sources, no
reusable copy, and inconsistent as-of reads across covenants of the same CP.

US-DS-11 already provides the durable staging copy (`cov_staging_value`) and obligor→CP mapping, and
US-DS-12 adds multi-year REST series fetch. **This story governs *when and what* to ingest** so the
staging copy is populated efficiently. The governing principle is **ingest just-ahead-of-need, gated
by freshness**: each candidate ingestion is a *decision*, not a fixed daily job. A blind daily full
pull is simply this logic with the freshness gate hard-wired to "always true" — this story removes
that waste.

**Explicitly rejected alternatives (see US-DS-11 discussion):** *fetch per covenant execution*
(≈ 500× redundant, non-isolating, inconsistent as-of) and *daily full pull of the whole portfolio*
(≈ 99% wasted extraction on data that changes quarterly).

### The freshness gate (core decision)
For each `(entity_key, data_source_table)` under consideration, resolve three dates and decide:
- **`required_as_of`** — the as-of date the *due* covenants need (from assessment date / widest
  aggregation window).
- **`staged_as_of`** — the max `as_of_date` already in `cov_staging_value` for that entity+source
  (the watermark).
- **`source_changed_at`** — when the source last published new/revised data for that entity, *if the
  source can report it* (a CDC / `last_updated` column or change feed).

> **Ingest only if** `staged_as_of` is missing **OR** `staged_as_of < required_as_of` **OR**
> `source_changed_at > staged_ingested_at`. **Otherwise skip.**

### Triggers (tiered — what runs the gate against which entities)
| Trigger | Logic | Use for |
|---|---|---|
| **Due-driven (primary)** | From the monitoring schedule, find covenants due within a lookahead horizon (e.g. next 1–7 days); derive their `(entity, measure-set, required_as_of)`; run the gate on those entities only. | The workhorse. |
| **Freshness sweep** | Periodically scan entities whose `staged_as_of` is older than their covenant's reporting period allows. | Drift / safety net. |
| **Calendar / event** | On reporting-calendar boundaries (period-end + a statement-arrival lag) or a push/CDC signal, mark affected entities dirty and ingest. | Obligor financials on known cadences. |
| **On-demand / force** | Manual run or an explicit "force refresh" sets `source_changed_at = now`, bypassing the gate for one entity. | Interactive single-CP runs; restatements. |

### Lookahead (pre-stage) window
Ingestion runs **ahead** of evaluation, not at the moment of evaluation (keeps them decoupled). A
scheduled job asks "which entities have a covenant due within the horizon **and** a stale gate?" and
pre-stages only those. The evaluation run then finds everything staged and reads locally.

### Coalescing (collapse demand before fetching)
Before any extract: group due-and-stale covenants by **entity** (obligor grain first via US-DS-11
mapping, then CP for facility-grained data); **union their required `item_code`s** into one
measure-set per entity; take the **widest `required_as_of` window**; issue **one** extract per
`(entity, source)`. So 500 covenants sharing one obligor become **one** fetch.

### Delta / incremental within an entity
- **First time** (no `staged_as_of`) → pull the full window the widest covenant needs (US-DS-12 series
  for REST; full history for DB).
- **Steady state** → pull only dates newer than `staged_as_of` (`from = staged_as_of + 1`).
- **Restatement / force** → full re-pull of the window; delete-then-insert overwrites idempotently.

### Source-type handling (same gate, different fetch shape)
- **Database** sources return full history cheaply (`WHERE key=? AND as_of <= ?`); first-time full
  window is effectively free.
- **REST** sources are snapshot-per-call; the delta step fetches the *series* of missing dates
  (US-DS-12), never the whole history each run.

## Acceptance criteria

### AC1 — Evaluation never calls the source
```
Given a covenant is evaluated (scheduler or manual run)
Then it resolves values only from cov_staging_value and never issues an API/DB call to the source
And if the staged copy is missing/stale, that surfaces as a data-quality signal, not a source call mid-evaluation.
```

### AC2 — Freshness gate skips fresh data
```
Given an entity+source whose staged_as_of already satisfies the due covenants' required_as_of
And no newer source_changed_at than the staged ingest
When the ingestion job considers it
Then it is skipped (no extract issued).
```

### AC3 — Gate ingests when missing / stale / changed
```
Given an entity+source where staged_as_of is missing, or < required_as_of, or source_changed_at > staged_ingested_at
When the ingestion job runs
Then exactly one extract is issued for that entity+source and staging is updated.
```

### AC4 — Due-driven lookahead pre-staging
```
Given covenants due within the lookahead horizon
When the pre-stage job runs ahead of the evaluation run
Then only entities with an imminent due date AND a stale gate are ingested
And the subsequent evaluation run finds them already staged (pure local read).
```

### AC5 — Coalesced, entity-grain, deduped extract
```
Given many due covenants on the same entity (and CPs sharing one obligor)
When ingestion runs
Then one extract per (entity, source) is issued covering the union of required item_codes and the widest window
And obligor-grain data is fetched once and fanned out to the obligor's CPs (US-DS-11).
```

### AC6 — Incremental cursor in steady state
```
Given staging already holds history for (entity, source)
When ingestion runs again
Then only dates newer than staged_as_of are fetched (full window only on first run or force-refresh)
And re-ingesting an overlapping range overwrites, not duplicates (delete-then-insert; unique key holds).
```

### AC7 — Cadence tiered by data class, not a blanket daily pull
```
Given data classes with different volatility (obligor financials quarterly; facility data daily)
Then each class refreshes on its own cadence (calendar/event/freshness), not one monolithic daily full pull
And a max-staleness TTL per data class re-pulls when a source cannot report source_changed_at.
```

### AC8 — Late-arriving / restated data
```
Given a source revises a prior period (source_changed_at moves past staged_ingested_at)
When the gate next runs for that entity
Then the affected window is re-pulled and overwritten idempotently
And a force-refresh achieves the same on demand for one entity.
```

### AC9 — Failure isolation & backpressure
```
Given a source outage or a slow/failing entity extract
Then that entity is marked stale and retried independently, without blocking other entities or the evaluation run
And the candidate entity list is paged with bounded concurrency and per-source rate limiting / circuit breaking in the ingestion layer.
```

### AC10 — Point-in-time integrity & audit
```
Given an entity is ingested
Then it lands one consistent as-of cut per ingest
And each assessment's immutable cov_assessment.data_snapshot_json still captures exactly what that run evaluated (US-036 unchanged).
```

### AC11 — Scale target (benchmark)
```
Given ~5,000 CPs (~3,000 obligors) × ~500 covenants with mixed frequencies
When a nightly due-driven run executes with a short lookahead
Then only entities that are both due-soon and stale are ingested (tens of entities on a typical night),
    versus ~2.5M per-covenant calls or ~3,000+ daily-full-pull calls
And quarter-end calendar refreshes are a bounded, expected burst (one call per obligor).
```

## Data / API notes
- **No new business schema is strictly required** — the watermark is `MAX(as_of_date)` per
  `(data_source_table_id, entity_key)` in `cov_staging_value` (US-DS-11). A small **ingestion-control
  table** (e.g. `cov_ingestion_watermark` / `cov_ingestion_state`: entity_key, data_source_table_id,
  staged_as_of, staged_ingested_at, source_changed_at, last_status, next_eligible_at) is recommended
  to make the gate/skip decision cheap and auditable without scanning staging each time; must be
  `cov_`-prefixed and MSSQL/H2-compatible.
- **Ingestion is orchestrated independently of evaluation** — a scheduled pre-stage job (its own
  Quartz trigger) separate from the breach/evaluation run; different failure domains.
- **Trigger inputs:** the monitoring schedule (due dates, US-MON-08/09), the reporting calendar /
  fiscal settings (US-AGG-11) for period boundaries, and the obligor→CP mapping (US-DS-11) for grain.
- **`source_changed_at`** comes from a source CDC/`last_updated` column or change feed where
  available; otherwise a per-data-class **max-staleness TTL** substitutes.
- **Extract mechanics** reuse `StagingIngestionService` (US-DS-11) and `fetchSeries` (US-DS-12);
  this story adds the *selection + gating + coalescing + scheduling* layer above them.
- **Config:** lookahead horizon (days), per-data-class TTL, page size, per-source concurrency /
  rate-limit caps — externalised, not hard-coded.

## Interaction with other features
- **US-DS-11:** consumes the staging store, obligor mapping, and idempotent delete-then-insert; turns
  US-DS-11's *JIT-at-evaluation* ingestion into *pre-staged, gated* ingestion (evaluation no longer
  triggers fetches).
- **US-DS-12:** uses the incremental series cursor for REST delta pulls.
- **US-AGG-11:** `required_as_of` and window math follow the same fiscal-aligned period boundaries.
- **US-MON-08 / US-MON-09:** the due-date schedule is the primary trigger input.
- **US-MON-14 (daily breach run):** evaluation reads staged data only; the breach run's scale fixes
  (paging, batched latest-executedAt, bulk cache read) complement this story.
- **US-036 (immutable snapshot):** unchanged — staging is the execution copy; the snapshot is the
  audit copy.

## Dependencies
- **US-DS-11** and **US-DS-12** delivered first.
- The monitoring schedule must expose due dates within a queryable horizon.
- For CDC-style freshness, the source must expose a change timestamp/feed; otherwise the TTL fallback
  applies.

## Open questions (confirm with product / ops)
- **Lookahead horizon** default (1 day vs a few days) and whether it is per-data-class.
- **Per-data-class TTLs** and the mapping of measures/tables to data classes (financial vs facility).
- **Force-refresh entry point** — admin action, a table flag, or both.
- **Watermark store** — dedicated `cov_ingestion_state` table vs deriving purely from
  `MAX(as_of_date)` on staging (trade-off: query cost vs an extra table to maintain).

## Out of scope
- The extract mechanics themselves (US-DS-11 / US-DS-12).
- A streaming/CDC ingestion pipeline (this story is scheduled/gated batch; CDC is only a freshness
  *signal* input here).
- Changing evaluation semantics or the immutable snapshot.
