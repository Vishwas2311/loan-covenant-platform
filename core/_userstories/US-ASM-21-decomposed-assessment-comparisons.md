# US-ASM-21 — Decomposed per-comparison assessment values (measure / operator / threshold / actual)

**Epic:** D — Formula & assessment semantics
**Status:** Draft · **Priority:** Medium

## Story
**As a** Compliance Officer / Reporting user
**I want** each assessment to persist, per comparison, the measure, operator, threshold value and the
actual (as-of-CP computed) value in dedicated columns
**so that** breach reports, headroom analytics and audits can read the exact numbers that were compared
without re-parsing the condition expression string.

## Description
When a covenant is assessed for a CP, the system evaluates its effective per-CP condition expression.
An expression contains one or more **comparisons** (a measure/aggregation on the left, an operator, and
a threshold on the right). Today only the overall result, a single tracked `computed_value`, the
rendered `evaluated_expression` and the raw `data_snapshot_json` are stored — so the individual
measure, operator, threshold and actual value are only available implicitly and must be re-parsed out
of the expression string.

This story adds a child record **per comparison** that captures the four decomposed parts **as
evaluated for that CP at that assessment time**, so the data is queryable and immutable alongside the
assessment. Because an expression can contain more than one comparison (e.g. `DSCR > 1.5 AND ICR > 2`),
a child table with one row per comparison is used rather than flat columns on the assessment.

The decomposition reuses `FormulaEngine.thresholds(expression)` (measure = `label`, `operator`,
threshold = `displayValue`, `valueType`); the **actual** value is the left-hand side evaluated against
the same resolved measure map used to produce the result. Rows are created only for AUTO assessments
that produced a resolvable expression; MANUAL and errored assessments create no comparison rows.

## Acceptance criteria

### AC1 — One row per comparison
```
Given an AUTO covenant with condition "DSCR > 1.5 AND ICR > 2" is assessed for a CP
When the assessment completes
Then two comparison rows are persisted for that assessment:
    (measure="DSCR", operator=">", threshold="1.5", actual=<CP DSCR value>, value_type=NUMBER)
    (measure="ICR",  operator=">", threshold="2",   actual=<CP ICR value>,  value_type=NUMBER)
And each row links to its parent assessment id and its ordinal position (0, 1, ...).
```

### AC2 — Single-comparison covenant
```
Given an AUTO covenant with condition "DSCR >= 1.25" is assessed
Then exactly one comparison row is persisted with measure "DSCR", operator ">=",
    threshold "1.25", and the actual DSCR value computed against the CP's data
And its actual value equals the existing assessment computed_value (parity with today's tracked value).
```

### AC3 — Actual reflects the CP's resolved data
```
Given a covenant is assessed against a CP's time-series data
Then the stored actual value for each comparison is the left-hand measure/aggregation
    evaluated with the same resolved measure map used to decide the result
And the stored value_type matches the computed type (NUMBER / STRING / BOOLEAN / DATE).
```

### AC4 — Manual and errored assessments
```
Given a MANUAL covenant assessment, or an AUTO assessment that failed to resolve/compile
Then no comparison rows are created
And the assessment still persists its result and error_message as today (no crash).
```

### AC5 — Non-comparison / aggregate-only expressions
```
Given an expression contains no threshold comparison (e.g. a bare boolean measure)
Then zero comparison rows are created
And the assessment persists normally.
```

### AC6 — Immutability & read API
```
Given comparison rows exist for an assessment
Then they are append-only (written once at assessment time, never updated or deleted)
And the assessment read API exposes the comparison rows
    (measure, operator, threshold, actual, valueType, ordinal) alongside the assessment.
```

### AC7 — Backward compatibility
```
Given assessments created before this feature
Then they simply have no comparison rows
And loading, monitoring, history and manual completion continue to work unchanged.
```

## Data / API notes
- New append-only table **`cov_assessment_threshold`**:
  - `id` IDENTITY PK; `assessment_id` FK -> `cov_assessment(id)` NOT NULL; `ordinal` INT NOT NULL
    (position of the comparison in the expression, 0-based);
  - `measure` VARCHAR(512) NOT NULL (the left-hand `FormulaThreshold.label`);
  - `operator` VARCHAR(8) NOT NULL (`>`, `>=`, `==`, `<`, `<=`, ...);
  - `threshold_value` VARCHAR(512) NOT NULL (`FormulaThreshold.displayValue`);
  - `actual_value` VARCHAR(512) NULL (evaluated left-hand value; null if unresolved);
  - `value_type` VARCHAR(16) NOT NULL (`NUMBER` / `STRING` / `BOOLEAN` / `DATE`);
  - named PK/FK constraints, unique `(assessment_id, ordinal)`, MSSQL + H2 `MODE=MSSQLServer`
    compatible (`IDENTITY`, `DATETIME2`/`BIT` where applicable, named CK on `value_type`). New Flyway
    migration `V<next>` in `db/migration` — do **not** edit released migrations.
- Populated in `service/AssessmentService.runAssessment()` after the result is computed, reusing
  `FormulaEngine.thresholds(effectiveExpression)` and evaluating each comparison's `label` against the
  already-resolved measure `values` map (the same map used by `populateComputedValue`).
- Exposed on the assessment read model (e.g. `AssessmentResponse.comparisons: []`); DTOs are
  coverage-excluded, but the population/mapping logic must stay covered (JaCoCo 0.95 line / 0.90
  branch).

## Dependencies / related
- Reuses the formula decomposition from Epic D (`FormulaEngine.thresholds`, `FormulaThreshold`) and the
  assessment pipeline (`AssessmentService`).
- Complements US-MON-16 **headroom** (single-comparison numeric distance): these rows give the raw
  measure/threshold/actual from which headroom can be derived, per comparison.
- Follows the cross-cutting "Audit & immutability" and "`cov_` table prefix" rules in this README.

## Out of scope
- Recomputing/backfilling comparison rows for historical assessments.
- UI to display the decomposed rows (data + API only; a grid/report is a separate story).
- Changing engine evaluation semantics or the `computed_value` / `evaluated_expression` columns (kept
  for compatibility).
