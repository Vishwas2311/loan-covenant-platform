# US-AGG-11 — Period-aware windowed aggregation (flat vs period-end)

**Epic:** Formula & assessment semantics
**Status:** Draft · **Priority:** Medium

## Story
**As a** Covenant Administrator / Analyst authoring an AUTO covenant
**I want** to choose whether a windowed aggregation averages **every reading** in the window or the
**one period-end figure per period** (e.g. the quarter-end value), with quarter/year boundaries
following our organisation's **calendar or fiscal** year
**so that** a condition like "average DSCR over the last 3 quarters" means the average of the three
*quarterly* figures — as a credit analyst reads it — and not a flat average of every intra-quarter
data point.

## Description
Covenant conditions aggregate a measure over a window, e.g. `AVG(PAT, '3Q')`. Today the engine
resolves this as a **flat aggregate over every row** whose as-of date falls in a rolling date range
(`MeasureAggregationResolver` runs `SELECT AVG(CAST(col AS DECIMAL(38,6))) … WHERE asOf >= asOf−9mo
AND asOf <= asOf`). It does **not** collapse each quarter to a representative figure.

That is correct for some covenants but wrong for many. "Average of the last 3 quarterly DSCRs" means:
take the **quarter-end** figure for each of the last three quarters, then average those three numbers.

Worked example — a CP with these readings (as-of 2026-03-31):

| Period | Readings | Quarter-end (latest in period) |
|--------|----------|--------------------------------|
| Q1 | 1.5, 2.5 | 2.5 |
| Q2 | 2.0, 2.7, 3.2 | 3.2 |
| Q3 | 2.0 | 2.0 |

- **Flat (today):** `(1.5+2.5+2.0+2.7+3.2+2.0)/6 ≈ 2.317`
- **Period-end (desired):** `(2.5+3.2+2.0)/3 ≈ 2.567`

Aggregation is really **three levers** the product should let users control independently:

| Lever | Question | Decided where |
|-------|----------|---------------|
| **Sampling mode** | Use *every* reading, or *one period-end figure per period*? | **Per formula** (covenant author's intent) |
| **Reporting calendar** | Do quarter/year boundaries follow calendar (Jan–Mar) or fiscal (e.g. Apr–Mar)? | **Per organisation** (one setting, applied everywhere) |
| **Trailing-period inclusion** | Does a trailing window count the *in-progress* period, or only *completed* periods? | **Org default + per-formula override** |

### Lever 1 — Sampling mode (per aggregation, backward compatible)
Add an **optional third argument** to an aggregation; it defaults to `FLAT`, so **every existing
formula and assessment is unchanged**:

| Formula | Meaning |
|---------|---------|
| `AVG(PAT, '3Q')` | **FLAT** (default, today) — aggregate all readings in the rolling window |
| `AVG(PAT, '3Q', PERIODIC)` | **PERIODIC**, inclusion **follows the org default** — one period-end figure per period, then aggregate |
| `AVG(PAT, '3Q', PERIODIC_CURRENT)` | PERIODIC, **override**: always include the in-progress (as-of) period |
| `AVG(PAT, '3Q', PERIODIC_COMPLETED)` | PERIODIC, **override**: only completed periods (period end ≤ as-of) |

- The **period granularity is inferred from the window unit**: `3Q`→quarters (N=3), `2Y`→years
  (N=2), `6M`→months (N=6). No separate granularity knob.
- **PERIODIC is supported only for period-capable units** `M`, `Q`, `Y`. `PERIODIC` with `D`/`W`
  (or with no window) is a validation error.
- **Inner reduction is the period-end value** = the *latest reading within that period*. (Fixed for
  v1; per-period AVG/MIN/MAX is a possible later extension.)
- Sampling is **ignored for `LATEST`/`CURRENT`** (a single most-recent value has no per-period sense).

### PERIODIC window meaning — the last N periods (anchored at the as-of date)
`AVG(m,'3Q',PERIODIC)` at an as-of date uses the **N most recent periods up to and including the
period that contains the as-of date**, not a raw rolling range:
- Within each period the resolved figure is the **period-end** value = the *latest reading dated ≤
  as-of* in that period.
- Whether the **in-progress** period (the as-of date's own period, when the as-of date is before its
  end) counts is set by the **trailing-period inclusion** policy (Lever 3): *include current* makes it
  the newest of the N; *completed only* excludes it and the newest becomes the most recent period
  whose end ≤ as-of. When the as-of date is itself a period end, both agree.
- A period with **no reading contributes nothing** (it is skipped, not zero). `COUNT` = number of
  periods that had a reading. If **no** period has data → value is **not present** (NA), identical to
  today's missing-data handling.

Examples (N=3 quarters, **fiscal** year starting April):
- as-of `2026-03-31` (a fiscal Q4 end) → Q4 FY25-26 (Jan–Mar 26), Q3 (Oct–Dec 25), Q2 (Jul–Sep 25)
  under **either** policy (the period is complete).
- as-of `2026-02-15` (mid fiscal Q4):
  - **include current** (default) → Q4 (Jan–Mar 26, latest reading ≤ 15 Feb), Q3 (Oct–Dec 25), Q2 (Jul–Sep 25).
  - **completed only** → Q3 (Oct–Dec 25), Q2 (Jul–Sep 25), Q1 (Apr–Jun 25).

### Lever 2 — Reporting calendar (one organisation setting)
A single **fiscal-year-start-month** (1–12) defines quarter/year boundaries for **all** PERIODIC
evaluations:
- `1` = **Calendar** (quarters Jan–Mar, Apr–Jun, …).
- `4` = **Fiscal** (India: Apr–Jun, Jul–Sep, Oct–Dec, Jan–Mar); any other start month is allowed.

The settings screen exposes it as a **Calendar / Fiscal** toggle (Fiscal reveals the start month,
default April). Monthly (`M`) periods are calendar months regardless; only `Q`/`Y` alignment shifts.
The default is **Calendar (1)**, so behaviour is neutral until an org opts into fiscal.

### Lever 3 — Trailing-period inclusion (org default + per-formula override)
Whether a PERIODIC window counts the **in-progress period** (the period containing the as-of date,
when the as-of date is before that period's end) is an organisation **policy** with a per-formula
**override**:
- Org setting `periodicIncludesCurrentPeriod` (default **true** = include the current period) sets the
  bank-wide default; a **Reporting policy** toggle exposes it beside the calendar setting.
- A formula overrides the default with an explicit keyword — `PERIODIC_CURRENT` (force include) or
  `PERIODIC_COMPLETED` (force completed-only). Plain `PERIODIC` follows the org default.
- **Effective inclusion** = the formula override if present, else the org default. It only changes
  which period is the *newest* of the N; the rest of the PERIODIC logic is identical. When the as-of
  date lands exactly on a period end, that period is complete and every policy agrees.
- Because PERIODIC is a **new** feature there is no legacy behaviour to preserve, so the default is
  chosen for intuitiveness (**include current** — and it degrades to the same result when the current
  period has no reading yet, since an empty period is skipped).

### Assessment resolution (PERIODIC)
Period selection is computed **in Java** from the as-of date, the fiscal-start setting, and the
effective trailing-period inclusion (the newest period is the as-of date's own period when *include
current*, else the most recent period whose end ≤ as-of; the N periods form one contiguous date range
`[oldestStart, newestEnd]`), then a single window-function query picks the latest row per period and
aggregates — for **both** COLUMNAR and KEY_VALUE (US-DS-10) tables:
```
SELECT <OUTER_AGG>(v) FROM (
  SELECT <value_expr> AS v,
         ROW_NUMBER() OVER (PARTITION BY <periodKeyExpr> ORDER BY <asOf> DESC) AS rn
  FROM <table>
  WHERE <entityKey> = ?               -- bound
    [AND <codeColumn> = ?]            -- bound, KEY_VALUE only
    AND <asOf> BETWEEN ? AND ?        -- bound: oldest period start .. as-of
) t
WHERE rn = 1;
-- <periodKeyExpr> applies the fiscal offset, e.g. bucket YEAR/QUARTER of DATEADD(MONTH, -(S-1), asOf)
```
Identifiers are still validated as strict SQL identifiers; all dates and the KEY_VALUE code are
**bound `?` parameters** (never concatenated). FLAT continues to use today's exact query.

### Authoring & settings UI
- **Formula builder:** when inserting an aggregation that has a window, show a **Sampling** control —
  "All readings" (FLAT) / "Period-end value" (PERIODIC) — disabled for `LATEST`/`CURRENT` and for
  `D`/`W` windows. When PERIODIC is chosen, a secondary **Trailing periods** control offers "Use org
  default" / "Include current period" / "Completed periods only" — the latter two emit the
  `PERIODIC_CURRENT` / `PERIODIC_COMPLETED` keywords. The choice is written into the formula text
  (FLAT omitted; the PERIODIC keyword explicit) so the expression stays self-describing and auditable.
- **Settings screen — Reporting policy:** a **Reporting calendar** Calendar/Fiscal toggle (+ fiscal
  start month) *and* a **Trailing periods include the in-progress period** Yes/No toggle
  (`periodicIncludesCurrentPeriod`), both backed by org settings; changing either re-evaluates future
  assessments consistently.

## Acceptance criteria

### AC1 — Sampling mode is optional and defaults to FLAT
```
Given an aggregation term with no sampling argument
Then it is treated as FLAT
And it resolves exactly as today (no behaviour or rendering change).
```

### AC2 — FLAT semantics unchanged
```
Given AVG(m, '3Q')            -- or SUM/MIN/MAX/COUNT
Then all readings with as-of in (as-of − 9 months, as-of] are aggregated equally,
    identical to the current engine.
```

### AC3 — PERIODIC = period-end per period, then aggregate
```
Given AVG(PAT, '3Q', PERIODIC) for a CP at as-of 2026-03-31
And readings Q1{1.5,2.5}, Q2{2.0,2.7,3.2}, Q3{2.0}
Then the engine uses the quarter-end (latest-in-period) value per quarter → 2.5, 3.2, 2.0
And the result is their average ≈ 2.567
    (contrast: FLAT would give ≈ 2.317).
```

### AC4 — Last N periods, anchored at the as-of date
```
Given a PERIODIC aggregation with window amount N and a period unit
Then the N most recent periods up to and including the as-of date's period are considered
And each period's figure is the latest reading dated <= as-of (empty periods skipped, see AC6)
And whether the in-progress period counts is set by the trailing-period inclusion policy (AC4b).
```

### AC4b — Trailing-period inclusion (org default + per-formula override)
```
Given the org setting periodicIncludesCurrentPeriod (default true = include current)
And a PERIODIC aggregation
Then plain PERIODIC follows the org default
And PERIODIC_CURRENT forces the in-progress period to count (newest period = as-of's period)
And PERIODIC_COMPLETED forces completed-only (newest period = most recent whose end <= as-of)
And when as-of falls on a period end, every policy yields the same periods.
```
Worked example (N=3 quarters, fiscal start April, as-of 2026-02-15 mid Q4):
- include current -> Q4 (Jan-Mar 26, latest reading <= 15 Feb), Q3 (Oct-Dec 25), Q2 (Jul-Sep 25)
- completed only  -> Q3 (Oct-Dec 25), Q2 (Jul-Sep 25), Q1 (Apr-Jun 25)

### AC5 — Reporting calendar drives period boundaries
```
Given the organisation setting fiscalYearStartMonth
When it is 1 (Calendar) vs 4 (Fiscal)
Then quarter/year period boundaries shift accordingly for every PERIODIC evaluation
And the same formula can yield different (correct) results under each setting.
```

### AC6 — Missing periods & NA handling (PERIODIC)
```
Given a PERIODIC window of N periods
Then a period with no reading contributes no value (it is skipped)
And COUNT returns the number of periods that had a reading
And when no period in the window has data, the value is "not present" (NA), as today.
```

### AC7 — Validation
```
Given formula validation (AUTO only)
Then an unknown sampling keyword is rejected (INVALID_SAMPLING)
And the accepted keywords are FLAT, PERIODIC, PERIODIC_CURRENT, PERIODIC_COMPLETED
And any PERIODIC variant without a window, or with a D/W window, is rejected (INVALID_WINDOW)
And any PERIODIC variant on LATEST/CURRENT is accepted but ignored (no-op)
And existing numeric-inner rules are unchanged (AVG/SUM/MIN/MAX numeric; COUNT/LATEST any).
```

### AC8 — Rendering / round-trip
```
Given a compiled formula
Then FLAT aggregations render WITHOUT a sampling argument (existing formulas are byte-identical)
And each PERIODIC variant renders its explicit keyword (PERIODIC / PERIODIC_CURRENT / PERIODIC_COMPLETED)
And parse→render→parse is stable.
```

### AC9 — Works for both table layouts
```
Given a PERIODIC aggregation
Then it resolves correctly for a COLUMNAR measure (source_column)
And for a KEY_VALUE measure (US-DS-10: value column + code filter), with the code bound as a parameter.
```

### AC10 — SQL safety & portability
```
Given a PERIODIC resolution
Then table/schema/column identifiers are validated before use
And period bounds, the entity key, and any KEY_VALUE code are bound as query parameters
And the query runs on SQL Server and on H2 in MODE=MSSQLServer (window function + fiscal date shift verified).
```

### AC11 — Backward compatibility
```
Given covenants, formulas and assessments created before this feature
Then they behave exactly as before (FLAT); the default reporting calendar is Calendar (start month 1)
And the trailing-period inclusion default applies only to new PERIODIC formulas (no legacy formula uses PERIODIC)
And no migration changes an existing stored expression.
```

### AC12 — UI exposure
```
Given the formula builder inserting a windowed aggregation
Then a Sampling control (All readings / Period-end) is offered, disabled where not applicable
And when Period-end is chosen, a Trailing-periods control (Org default / Include current / Completed only) is offered
And a Settings screen exposes the Calendar/Fiscal reporting-calendar toggle and the trailing-period inclusion toggle.
```

## Data / API notes
- **Formula engine** — new `SamplingMode { FLAT, PERIODIC }` plus `PeriodInclusion { DEFAULT,
  INCLUDE_CURRENT, COMPLETED_ONLY }`; `Node.Aggregation` (and `AggregationRequest`) carry both.
  `Lexer`/`Parser` accept an optional 3rd keyword argument, mapping `FLAT`→(FLAT); `PERIODIC`→(PERIODIC,
  DEFAULT); `PERIODIC_CURRENT`→(PERIODIC, INCLUDE_CURRENT); `PERIODIC_COMPLETED`→(PERIODIC,
  COMPLETED_ONLY). `FormulaRenderer` omits FLAT and emits the exact PERIODIC keyword;
  `FormulaTypeChecker` enforces AC7; `FormulaEngine`/metadata expose the keywords for the builder.
- **Resolver** — `MeasureAggregationResolver` gains a PERIODIC branch: resolve effective inclusion
  (formula override else org default), compute the N-period date range + a fiscal-aware period-key
  expression, run the `ROW_NUMBER() OVER (PARTITION BY period ORDER BY asOf DESC)` + outer-aggregate
  query; FLAT path unchanged.
- **Org settings** — persist `fiscalYearStartMonth` (default `1`) and `periodicIncludesCurrentPeriod`
  (default `true`). *Open (impl):* store as scalar app settings (a small `cov_setting`/`cov_app_setting`
  table) vs. reuse `cov_reference_data` (US-DATA-06). A settings API (`GET`/`PUT`) exposes them; the
  values are also surfaced in the formula metadata endpoint for the builder. MSSQL-compatible
  migration (new versioned file; runs on H2 MSSQL mode).
- **No change** to the assessment output shape, threshold overrides, or the measure/table model.

## Interaction with other features
- **US-DS-10 (KEY_VALUE tables):** PERIODIC works for both layouts; the code filter is added inside
  the windowed subquery (still a bound parameter).
- **Formula validation / AUTO-vs-MANUAL (US-CM-03, US-ASM-05):** sampling is validated only for AUTO;
  MANUAL covenants reference no measures and are unaffected.
- **Per-CP threshold override / recommendation (Epics A/B):** unaffected — only the value-resolution
  semantics change, not matching or thresholds.
- **Monitoring cadence (US-FREQ-07):** independent — the reporting calendar governs *aggregation*
  period boundaries, not the monitoring schedule's cron.

## Dependencies
- Builds on the formula DSL (`formula/**`) and `MeasureAggregationResolver`.
- The org reporting-calendar setting is most natural alongside reference data (US-DATA-06) but does
  not strictly depend on it.

## Assumptions & open questions (confirm with product)
- **Sampling lives in the formula** (per condition), **calendar lives in one org setting** — chosen
  for flexibility without re-specifying fiscal policy per formula. *Open:* do any orgs need a
  per-covenant calendar override? (Assumed no.)
- **Trailing-period inclusion = org default + per-formula override** (confirmed); default
  `periodicIncludesCurrentPeriod = true` (include the in-progress period), overridable in a formula via
  `PERIODIC_CURRENT` / `PERIODIC_COMPLETED`. *Open:* confirm the default direction with product.
- **Inner reduction = period-end (latest in period)** for v1. *Open:* demand for per-period
  AVG/MIN/MAX (a second, inner function) — deferred.
- **Keyword names** `PERIODIC` / `PERIODIC_CURRENT` / `PERIODIC_COMPLETED` (UI labels "Period-end
  value" + "Include current / Completed only"). *Open:* preferred wording.
- **Setting storage** — dedicated scalar settings vs reference-data category — impl decision above.
