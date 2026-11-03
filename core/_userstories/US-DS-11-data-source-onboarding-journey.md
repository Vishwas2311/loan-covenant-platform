# US-DS-11 — Data-source onboarding journey: layout UI, durable staging copy, obligor→CP mapping & scale

**Epic:** Data source & measure modeling
**Status:** Draft · **Priority:** High

## Story
**As a** Covenant Administrator / Integrator (and the RM whose covenants run on the result)
**I want** to register a data source and its tables end-to-end from the UI — choosing the physical
**layout** (columnar vs row/key-value) and the **entity-key level** (CP or obligor) — and have the
system land a **durable copy** of that source's data that covenants execute against, fanning
obligor-grain data down to each CP and scaling the daily breach run
**so that** onboarding a new source is smooth and self-service, assessments run on a stored,
repeatable copy (REST and MSSQL through one path) rather than live per-assessment SQL, and the
daily run survives large portfolios.

## Description
Onboarding a data source for covenant monitoring is today only *partially* self-service. The backend
supports both table layouts and has create/update/columns/code-value endpoints + API-client
functions, but **no screen ever calls them** (the acknowledged US-DS-10 AC6 gap). API/DB data is
never persisted as a durable copy — `MeasureAggregationResolver` runs SQL directly against the
*live* source table per assessment (MSSQL only; the REST client `WebClientDataApiClient` is wired
nowhere). And identity is **CP_ID-only**: `OBLIGOR_ID` exists nowhere in code or schema, so external
data that arrives at *obligor* grain cannot be fanned down to the CPs that execute covenants. The
daily breach run loads all applied covenants into memory (`findAll()`), does an N+1 latest-assessment
lookup per covenant, and makes a blocking per-CP HTTP lookup — none of which scales.

This story delivers **one sequenced journey** across three independently-shippable capabilities. The
covenant **formula/assessment layer is unchanged**; the immutable per-assessment snapshot (US-036)
stays exactly as-is as the audit copy.

```
Register source ─▶ Register table (UI: pick COLUMNAR/KEY_VALUE + entity-key level cp_id|obligor_id)
                                   │
CP lookup ─▶ capture obligor_id ─▶ cov_obligor_cp_map (1 obligor → many CPs)
                                   │
Assessment run ─▶ JIT ingest (MSSQL copy | REST fetch) ─▶ cov_staging_value (entity_key + as_of_date)
                                   │
Resolver reads staging (one path, REST+MSSQL) ─▶ per-CP result (obligor-keyed → fan-out via mapping)
                                   │
                        cov_assessment.data_snapshot_json  (unchanged immutable audit copy)
```

**Product decisions (confirmed):** cover all three areas as one sequenced journey; "persist a copy" =
a **dedicated staging store**; obligor relationship = **one obligor → many CPs**, `obligor_id`
captured at the CP lookup.

### Capability 1 — Columnar / row table-registration UI *(closes US-DS-10 AC6)*
A user can register and edit a **data-source table** from the UI, choosing its layout with a
segmented control (**Columnar** vs **Row / Key-Value**). For `KEY_VALUE`, code-column and
value-column fields appear (with an optional distinct-code preview); for `COLUMNAR` they are hidden
and cleared. Client validation mirrors the backend (`KEY_VALUE` requires both code+value; `COLUMNAR`
requires neither; SQL-identifier regex `^[A-Za-z_][A-Za-z0-9_]*$`). Because column/code discovery
only resolves for a *persisted* table, **create mode uses text inputs and edit mode upgrades
key/as-of/code/value to searchable selects** populated from discovery (the "Select-if-options-else-
TextInput" pattern already in the Measure form).

### Capability 2 — Durable staging copy + unified resolver
Instead of querying the live source per assessment, the system **lands a durable copy** into a
generic long/EAV store `cov_staging_value`, keyed by `(data_source_table_id, entity_key, as_of_date,
item_code)`. Ingestion is **just-in-time** at assessment time and **idempotent** (delete-then-insert
scoped by table+entity+as-of, so a re-run overwrites rather than duplicates). MSSQL sources copy live
rows via the existing JDBC read pattern; **REST sources finally work** by integrating
`WebClientDataApiClient.fetch(...)`. The resolver then reads `cov_staging_value` for **every** source
through one path — LATEST/CURRENT, FLAT aggregates, and PERIODIC windowing (fiscal-aware period math)
are preserved bit-for-bit; the KEY_VALUE code filter collapses into the single `item_code` predicate.

### Capability 3 — Obligor→CP mapping + scalable execution
A table declares an **entity-key level** — `cp_id` (default, today's behaviour) or `obligor_id`. The
CP lookup captures the upstream `obligor_id` and records a **one obligor → many CPs** mapping
(`cov_obligor_cp_map`). For an obligor-keyed table the resolver resolves the CP's obligor once and
binds it as `entity_key`, so one obligor's data is ingested once and **fanned out** to each of its
CPs (results still attach to the CP). No mapping ⇒ a clean "not present", never a wrong-CP read. The
daily breach run is made scalable: paged active-covenant sweep, a single batched latest-`executedAt`
query (kills the N+1), and a bulk `cov_credit_proposal_cache` read (obligor already denormalised
onto the cache) with HTTP only for misses.

## Acceptance criteria

### Phase 1 — Table registration UI

#### AC1 — List tables under a source
```
Given a registered data source
When I open it and choose "Tables"
Then I see its tables with name, table/schema, a layout badge, and status
And I can add a table, edit a table, or deactivate a table
And loading / error / empty states mirror the data-source list screen.
```

#### AC2 — Register a table and pick its layout
```
Given the "Add table" form
When I choose layout = Row / Key-Value
Then code-column and value-column fields are shown and required
When I choose layout = Columnar
Then those fields are hidden and cleared
And the form blocks save unless the fields required by the layout are provided and every
    identifier matches ^[A-Za-z_][A-Za-z0-9_]*$
And on save the table is created via the existing create endpoint and appears in the list.
```

#### AC3 — Create vs edit input affordances
```
Given the table form
When I am creating a new table
Then key/as-of/code/value are plain text inputs (discovery needs a persisted table)
When I am editing an existing table
Then those fields upgrade to searchable selects populated from column / code-value discovery
And a distinct-code preview is available for a KEY_VALUE table.
```

#### AC4 — Measure Library KEY_VALUE display fix
```
Given a KEY_VALUE measure (source_column is null, source_code is set)
When I view it in the Measure Library
Then it renders as tableName.<sourceCode> (not an empty tableName. with a missing column).
```

### Phase 2 — Durable staging copy + unified resolver

#### AC5 — Data is persisted to a durable staging copy
```
Given an assessment runs for a CP at an as-of date on any source (MSSQL or REST)
When the resolver needs a measure value
Then the source's data has first been ingested into cov_staging_value keyed by
    (data_source_table_id, entity_key, as_of_date, item_code)
And item_code = the measure's source_column (COLUMNAR) or source_code (KEY_VALUE)
And the resolver reads from cov_staging_value, not from the live source table.
```

#### AC6 — REST sources are assessable
```
Given a data source of type REST with a table registered
When an assessment runs
Then WebClientDataApiClient.fetch(entityKey, asOf, keys) is called and its DataValuesResponse
    is landed into cov_staging_value
And the covenant assesses off the stored copy exactly as an MSSQL source would.
```

#### AC7 — Idempotent ingestion
```
Given data already ingested for (table, entity, as-of)
When ingestion runs again for the same key
Then existing rows for that (data_source_table_id, entity_key, as_of_date) are replaced, not
    duplicated (delete-then-insert in one transaction)
And the unique key (data_source_table_id, entity_key, as_of_date, item_code) is never violated.
```

#### AC8 — Aggregation behaviour preserved bit-for-bit
```
Given measures resolved from cov_staging_value
Then LATEST/CURRENT returns the most recent value at/before the as-of date
And AVG/SUM/MIN/MAX/COUNT aggregate only the windowed rows (num_value, CAST AS DECIMAL(38,6))
And FLAT and PERIODIC sampling (empty-period skip / include-current / fiscal-offset) match the
    pre-staging results exactly
And a missing value yields present = false (same NA / missing-data handling as before).
```

#### AC9 — JIT ingestion once per (table, entity, as-of); snapshot unchanged
```
Given an assessment request touching several measures on the same table for one CP
When it runs
Then ingestion happens once per distinct (dataSourceTable, entityKey, today), not per measure
And cov_assessment.data_snapshot_json is still written as the immutable audit copy
And US-036 immutability holds (editing a completed assessment → 409 ASSESSMENT_IMMUTABLE).
```

### Phase 3 — Obligor→CP mapping + scale

#### AC10 — Table declares an entity-key level
```
Given the table registration form (now that the backend field exists)
Then a table declares entity_key_level of cp_id (default) or obligor_id
And existing tables default to cp_id with no behaviour change.
```

#### AC11 — Obligor captured at CP lookup
```
Given a CP lookup whose upstream response includes an obligor id
When the lookup completes
Then an obligor→CP mapping is recorded (idempotent upsert on cp_id) in cov_obligor_cp_map
And the obligor id is shown on the lookup card and denormalised onto cov_credit_proposal_cache
And an upstream response without an obligor id deserialises with obligorId = null (no error).
```

#### AC12 — Obligor-keyed resolution & fan-out
```
Given an obligor-keyed table and two CPs mapped to the same obligor
When each CP is assessed
Then the resolver resolves the CP's obligor once and binds it as entity_key
And both CPs assess off the shared obligor staging rows, each result attached to its own CP
And a CP with no obligor mapping yields present = false (never a wrong-CP read).
```

#### AC13 — Daily breach run scales
```
Given a large portfolio of active applied covenants
When the daily breach run executes
Then active covenants are swept in pages (e.g. 500) rather than a single findAll()
And the latest executedAt per covenant is loaded via one batched query per page (no N+1)
And CP/obligor data is read via one bulk cov_credit_proposal_cache lookup, with HTTP only for misses
And audit rows are saved in bulk per run.
```

#### AC14 — Backward compatibility & safe backfill
```
Given tables, measures and CPs created before this feature
Then all tables are treated as entity_key_level = cp_id and behave exactly as before
And obligor_id starts NULL / the map empty; rows populate lazily as CPs are re-looked-up
    (cache TTL) or via a one-off admin backfill
And the NULL window is harmless because no obligor-keyed table exists until one is registered.
```

### Cross-cutting

#### AC15 — SQL safety & naming
```
Given staging read/write and any ingestion SQL
Then reading from fixed cov_staging_value columns removes dynamic table/column identifiers from the
    resolver (a SAST win); any remaining identifiers are validated as strict SQL identifiers and all
    values are bound parameters
And every new table is cov_-prefixed (cov_staging_value, cov_obligor_cp_map)
And DDL is MSSQL-compatible and also runs on H2 MODE=MSSQLServer with ddl-auto: validate.
```

## Data / API notes
- **Migration `V4__staging_value.sql`** — one generic long/EAV table (chosen over per-source tables:
  measures are user-registered at runtime, so dynamic DDL would break `ddl-auto: validate`):
  `cov_staging_value(id, data_source_table_id FK→cov_data_source_table, entity_key VARCHAR(128),
  as_of_date DATE, item_code VARCHAR(128), num_value DECIMAL(38,6), str_value VARCHAR(2000),
  date_value DATE, bool_value BIT, data_type VARCHAR(16) CHECK number/string/date/boolean,
  ingested_at, ingest_batch_id, + audit cols)`; `UNIQUE (data_source_table_id, entity_key, as_of_date,
  item_code)` (idempotency anchor); `INDEX (data_source_table_id, entity_key, item_code, as_of_date)`
  (matches the resolver WHERE/ORDER-BY shape).
- **Migration `V5__obligor_cp_mapping.sql`** — `cov_obligor_cp_map(id, obligor_id VARCHAR(64), cp_id
  VARCHAR(64), + audit)`, `UNIQUE (cp_id)` (many CPs → one obligor), `INDEX (obligor_id)` (fan-out);
  `ALTER cov_credit_proposal_cache ADD obligor_id VARCHAR(64) NULL` + index (forward cp→obligor copy);
  `ALTER cov_data_source_table ADD entity_key_level VARCHAR(16) NOT NULL DEFAULT 'cp_id'` + CHECK
  (`cp_id`/`obligor_id`).
- **Entities / repos** — `StagingValue` (`@Table("cov_staging_value")`, extends `AuditableEntity`,
  `@ManyToOne DataSourceTable`, `DataColumnType dataType`); `StagingValueRepository`
  (`deleteByDataSourceTableIdAndEntityKeyAndAsOfDate…`, bulk read); `ObligorCpMap` +
  `ObligorCpMapRepository` (`findByCpId`, `findCpIdsByObligorId`); `TableEntityKeyLevel`
  (`CP_ID`/`OBLIGOR_ID`) with an auto-applied lowercase converter.
- **Services** — `StagingIngestionService.ingest(table, entityKey, asOf)` (`@Transactional`,
  delete-then-insert; MSSQL via `JdbcDataSourceFactory` + `SqlIdentifiers` + `ColumnDiscoveryService`;
  REST via `WebClientDataApiClient.fetch`); `ObligorMappingService` (`recordMapping(cpId, obligorId)`
  idempotent + null-safe, `obligorFor(cpId)`).
- **Resolver** — reads `cov_staging_value` via the app `JdbcTemplate`; WHERE = fixed
  `data_source_table_id = ? AND entity_key = ? AND item_code = ?` + as-of bounds; value column by
  `data_type`; switches on `table.getEntityKeyLevel()` (CP_ID binds CP; OBLIGOR_ID binds resolved
  obligor).
- **Assessment** — JIT ingest once per distinct `(dataSourceTable, entityKey, today)`, resolving the
  obligor for obligor-keyed tables; snapshot write path & US-036 immutability untouched.
- **Breach run** — paged `AppliedCovenantRepository.findByStatus(ACTIVE, Pageable)`
  (`@EntityGraph({"covenantMaster"})`); batched `findLatestExecutedAtByAppliedCovenantIdIn(ids)`;
  bulk `cov_credit_proposal_cache.findAllById(cpIds)`; `saveAll(...)` audit rows.
- **Lookup capture** — `CreditProposalDetails` gains `String obligorId` (record ignores unknown JSON);
  `RecommendationController.lookup()` calls `ObligorMappingService.recordMapping(...)` after lookup.
- **Frontend** — new `getTable(id, tableId)` API fn + `useCreate/Update/Deactivate…TableMutation` and
  `useDataSourceTableQuery` hooks; `App.tsx` `View` variants for the table list & table form;
  "Tables" row action on the data-source list; entity-key-level control on the table form;
  `onboarding.ts` `obligorId: string | null` + obligor line on the CP onboarding lookup card.

## Interaction with other features
- **US-DS-10 (row/key-value layout):** this story delivers the missing **table-registration UI** (its
  AC6) and reuses its `layout` / `code_column` / `value_column` model unchanged.
- **US-AGG-11 (period-aware windowed aggregation):** PERIODIC period math and the org calendar/fiscal
  policy are preserved bit-for-bit; only the value *source* moves to staging.
- **US-036 (immutable assessment snapshot):** unchanged — `data_snapshot_json` remains the immutable
  audit copy; staging is the *execution* copy.
- **Recommendation / eligibility (Epic A):** unaffected — identity level and staging change only how a
  value is sourced, not how covenants are matched to CPs.
- **US-MON-14 (daily breach run):** its correctness is unchanged; only its scale profile improves
  (paging, batched latest-executedAt, bulk cache read).

## Dependencies
- Builds on the measure model (`cov_data_source_table`, `cov_measure`, `MeasureAggregationResolver`,
  `JdbcDataSourceFactory`, `ColumnDiscoveryService`) and US-DS-10's layout fields.
- REST path depends on `WebClientDataApiClient` / `DataApiClient.fetch` and the upstream Data API.
- Obligor capture depends on the credit-proposal lookup exposing an obligor id
  (`CreditProposalDetails.obligorId`); absent upstream support, obligor-keyed tables simply resolve
  "not present" until the mapping is backfilled.
- Prod migration numbering continues at **V4, V5** (after V1/V2/V3).

## Out of scope (flag for follow-up)
- **Multi-year REST fetch** — REST `fetch(...)` returns a single-date snapshot, so multi-year windows
  accrue only over successive assessments. A `fetchSeries(entityKey, from, to, keys)` /
  `DataSeriesResponse` design (auto range from the covenant window, incremental fetch) is specified in
  **US-DS-12** (its own story). MSSQL already copies full history on first ingest.
- A `discoverTables` endpoint (auto-listing source tables) — create mode stays free-text.
- A scheduled / bulk ingestion pipeline — ingestion remains JIT at assessment time.
- Obligor id fabrication / mandatory obligor backfill — mappings populate lazily; a one-off admin
  backfill is optional.
