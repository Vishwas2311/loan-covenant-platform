# US-DS-10 — Row / key-value (EAV) data source layout

**Epic:** Data source & measure modeling
**Status:** Draft · **Priority:** High

## Story
**As a** Covenant Administrator / Integrator
**I want** to register a data-source table whose measures are stored in **rows** (one value column
holding many measures, distinguished by a code column) as well as the existing **columnar** tables
**so that** covenants can be assessed against real customer sources that publish metrics in a
long / key-value (EAV) shape, without reshaping their data into one-column-per-measure.

## Description
Today the system assumes every data-source table is **columnar (wide)**: each measure maps 1:1 to a
physical column (`cov_measure.source_column`), and the assessment engine runs
`SELECT <col> FROM <table> WHERE <entityKey> = ? AND <asOf> BETWEEN ? AND ?`.

Many sources instead publish metrics **row-wise (long / key-value / EAV)** — a single *value* column
holds many measures, identified by a *code* column:

| CP_ID | Data As On Date | Ratio Code | Ratio Value |
|-------|-----------------|------------|-------------|
| CP-1001 | 31-Mar-26 | DSCR | 1.3 |
| CP-1001 | 31-Mar-26 | PAT | 0.5 |
| CP-1001 | 31-Mar-26 | Current_ratio | 2.3 |
| CP-1002 | 31-Mar-26 | DSCR | 1.8 |
| CP-1002 | 31-Mar-26 | PAT | 0.8 |
| CP-1002 | 31-Mar-26 | Current_ratio | 5.3 |

We must support **both** layouts. The covenant **formula/assessment layer is unchanged** — it works
purely on measure `code → value`; only the mapping from a measure to physical storage and the SQL
that fetches a value change.

### Layout is a property of the **table**
A data-source table declares a **layout**:
- **`COLUMNAR`** (default, today's behaviour) — each measure is a distinct column.
- **`KEY_VALUE`** — the table additionally names a **code column** (holds the measure identifier, e.g.
  `Ratio Code`) and a **value column** (holds the number/text, e.g. `Ratio Value`). The entity-key
  and as-of columns work exactly as before.

### The **measure** names the code it matches
Under a `KEY_VALUE` table, each measure stores the **code value** it corresponds to (e.g. `DSCR`) in
a new `source_code` field; `source_column` is not used. Under a `COLUMNAR` table it is the reverse:
`source_column` is used and `source_code` is empty. The measure `code` (formula-facing identifier)
stays decoupled from the physical `source_code`, so a formula code like `dscr_ratio` can map to a
source code value such as `DSCR` or `Current_ratio`.

### Assessment resolution
For a `KEY_VALUE` measure the engine resolves a value with the same windowing/aggregation as today,
adding a code filter:
```
SELECT TOP 1 <value_column> FROM <table>
 WHERE <entity_key_column> = ? AND <as_of_column> BETWEEN ? AND ? AND <code_column> = ?
 ORDER BY <as_of_column> DESC          -- LATEST/CURRENT
-- or  SELECT AVG(CAST(<value_column> AS DECIMAL(38,6))) ... same WHERE   -- AVG/SUM/MIN/MAX/COUNT
```
The code value is passed as a **bound `?` parameter**; every table/column identifier is still
validated as a strict SQL identifier before concatenation.

### Code discovery
Because measure codes live in *data* (not the schema), provide a way to list the distinct codes
present in a `KEY_VALUE` table (`SELECT DISTINCT <code_column> …`) so an admin can pick a measure's
`source_code` from a list instead of typing it blindly.

### Authoring UI (Measure form)
When the selected table is `KEY_VALUE`, the Measure form shows a **"Source code"** control
(populated from code discovery; free-text fallback when discovery is unavailable) in place of the
**"Source column"** control. `COLUMNAR` tables keep the current "Source column" picker. Registration
of `KEY_VALUE` tables themselves is via API/seed for now (there is no table-registration screen yet).

## Acceptance criteria

### AC1 — Table declares a layout
```
Given a request to register/update a data-source table
Then the table has a layout of COLUMNAR (default) or KEY_VALUE
And when layout = KEY_VALUE, a code column and a value column are required
And when layout = COLUMNAR, code/value columns must be empty
And an invalid combination is rejected with a 400 (INVALID_TABLE_LAYOUT).
```

### AC2 — Measure mapping matches its table's layout
```
Given a measure attached to a table
When the table is COLUMNAR
Then the measure requires a source column (source_code is ignored/cleared)
When the table is KEY_VALUE
Then the measure requires a source code value (source_column is ignored/cleared)
And a measure missing the field required by its table's layout is rejected with a 400.
```

### AC3 — Assessment reads the right value for a KEY_VALUE measure
```
Given a KEY_VALUE table (entity-key, as-of, code, value columns) with rows for several codes
And a measure whose source_code = 'DSCR'
When an assessment runs for a CP at an as-of date
Then only rows with code = 'DSCR' for that CP within the window are considered
And the resolved value equals the value column of the matching row(s).
```

### AC4 — Aggregations & windows behave identically to columnar
```
Given a KEY_VALUE measure
Then LATEST/CURRENT returns the most recent value at/before the as-of date for that code
And AVG/SUM/MIN/MAX/COUNT aggregate only the windowed rows for that code
And when no row matches the code in the window, the value is "not present"
    (yielding the same NA / missing-data handling as a columnar measure).
```

### AC5 — Distinct code discovery
```
Given a KEY_VALUE table
When the code-values discovery endpoint is called
Then it returns the distinct, non-null values of the table's code column, ordered
And calling it for a COLUMNAR table (no code column) returns a clear 400.
```

### AC6 — Measure form adapts to layout
```
Given the Measure create/edit form
When the chosen table is KEY_VALUE
Then a "Source code" control is shown (options from code discovery; free-text fallback), and
     the "Source column" control is hidden
When the chosen table is COLUMNAR
Then the existing "Source column" picker is shown
And the form only allows save when the field required by the layout is provided.
```

### AC7 — Backward compatibility
```
Given tables and measures created before this feature
Then they are treated as layout = COLUMNAR
And their assessments resolve exactly as before (no behaviour change).
```

### AC8 — SQL safety preserved
```
Given a KEY_VALUE resolution
Then table/schema/code/value column names are validated as strict SQL identifiers before use
And the measure's code value is bound as a query parameter, never concatenated into SQL.
```

### AC9 — Naming convention
```
Given the schema changes for this feature
Then new columns are added to the existing cov_ application tables (cov_data_source_table, cov_measure)
And any external row/EAV source used for the demo is NOT cov_-prefixed
    (it simulates a customer source, consistent with cp_metrics_demo — see project CLAUDE.md).
```

## Data / API notes
- **`cov_data_source_table`** — add `table_layout` (`VARCHAR(16)`, `COLUMNAR`/`KEY_VALUE`, NOT NULL,
  CHECK constraint, default/backfill `COLUMNAR`), `code_column` (`VARCHAR(128)`, nullable),
  `value_column` (`VARCHAR(128)`, nullable).
- **`cov_measure`** — `source_column` becomes **nullable**; add `source_code` (`VARCHAR(128)`, nullable).
- Migration is MSSQL-compatible and runs on H2 `MODE=MSSQLServer` (add→backfill→NOT NULL pattern, per
  the existing V17/V23 migrations); never edit a released migration — add a new versioned file.
- **API** — the table create/update payloads and responses carry `layout`, `codeColumn`,
  `valueColumn`; measure payloads/responses carry `sourceCode`. New endpoint:
  `GET /api/v1/data-sources/{dataSourceId}/tables/{tableId}/code-values` → `string[]` of distinct codes.
- **Validation branch** keys off the table's layout in `DataSourceTableService` (table shape) and
  `MeasureService` (measure mapping); the SQL branch is in `MeasureAggregationResolver`.

## Interaction with other features
- **Recommendation / eligibility (Epic A):** unaffected — layout only changes how a measure's value is
  fetched, not how covenants are matched to CPs.
- **Manual assessments (US-ASM-05):** unaffected — manual covenants reference no measures.
- **Per-CP threshold override / formula builder:** unchanged; formulas still reference measure `code`s.

## Dependencies
- Builds on the measure model (`cov_data_source_table`, `cov_measure`, `MeasureAggregationResolver`).
- No dependency on Epic A/B stories.
