# US-CM-23 — Formula Builder (AUTO condition authoring)

**Epic:** Covenant eligibility, classification & assessment type
**Status:** Draft · **Priority:** High

> **Consolidation note.** This is the single, canonical specification of the **Formula Builder** — the
> assisted editor used to author an AUTO covenant's condition. The builder was previously specified in
> fragments: US-CM-03 introduces it ("AC2 — Auto enables the formula builder + validation"), US-AGG-11
> adds the sampling control, US-CM-22 excludes it (custom covenants are MANUAL-only), and US-DS-10 notes
> it is measure-code driven. This story gathers **all** of those behaviours in one place. Where an
> acceptance criterion restates a source story it is cross-referenced; this story does not change the
> engine semantics defined in US-AGG-11.

## Story
**As a** Covenant Administrator / Analyst authoring an **AUTO** covenant
**I want** an assisted editor that lets me write a covenant condition over named measures — with
autocomplete, live syntax + type validation, a sample dry-run, a syntax guide, and a sampling control —
**so that** I can compose a correct, boolean measure formula (e.g. `AVG(PAT, '2y') > 1 AND DSCR > 1.2`)
without memorising the grammar and without saving something the engine cannot evaluate.

## Description
The Formula Builder is the editor shown for the **condition** field of a covenant whenever
**Assessment type = AUTO** (US-CM-03). It is a thin authoring surface over the measure-formula DSL
(`formula/**`, entry point `FormulaEngine`) and the measure catalog; it never evaluates covenants
itself — it validates and *sample*-evaluates while you type, exactly as the real engine would.

It appears in three places, all backed by the same `FormulaBuilder` component:
- **Covenant Library — covenant create/edit form** (primary): the AUTO condition editor.
- **Per-CP threshold override editor**: reuses the builder's threshold-extraction/apply endpoints to
  let a user retune the comparison constants of an applied covenant's formula.
- **Standalone Formula Builder page** (`FormulaBuilderPage`): a sandbox to compose/validate a condition
  in isolation.

The builder is **hidden and not applied** when Assessment type = MANUAL (US-CM-03 AC3) — including
CP-scoped custom covenants, which are always MANUAL (US-CM-22) — where the condition is free text.

### Capabilities (what the builder offers)
1. **Grammar palette (metadata-driven).** Aggregation functions, time-window units, and the US-AGG-11
   sampling keywords are fetched from `GET /api/v1/formulas/metadata` — the frontend hard-codes only a
   fallback used before metadata loads.
   - **Functions:** `AVG`, `SUM`, `MIN`, `MAX` (numeric inner → NUMBER); `COUNT` (any inner → NUMBER);
     `LATEST` / `CURRENT` (synonyms; most-recent value, preserves the measure's type).
   - **Window units:** `d` days, `w` weeks, `m` months, `q` quarters, `y` years (e.g. `'90d'`, `'6m'`,
     `'2y'`, `'3q'`).
   - **Sampling keywords** (optional 3rd aggregation argument, US-AGG-11): `FLAT` (All readings — the
     default, omitted when rendered), `PERIODIC` (period-end, org default inclusion), `PERIODIC_CURRENT`
     (include the in-progress period), `PERIODIC_COMPLETED` (completed periods only).
2. **Measure autocomplete.** As you type an identifier, a ranked suggestion list appears — active
   measures first (showing code, name, and a unit/data-type badge colour-coded by type), then functions,
   then logic keywords (`AND`/`OR`/`NOT`/`TRUE`/`FALSE`). Ranking is prefix-match before substring.
   Navigate with ↑/↓, accept with Enter/Tab, dismiss with Esc, or click. Suggestions are capped (8).
3. **Syntax highlighting.** A live, tokenised preview colours measures, functions, numbers, strings,
   operators, keywords, parentheses, and flags unknown tokens in red.
4. **Live validation.** The expression is validated (debounced) via `POST /api/v1/formulas/validate`
   with `requireBoolean=true` (a condition must be a true/false test). Errors are returned with
   character **offsets** so the editor can point at the offending span; the covenant cannot be saved
   while invalid (US-CM-03 AC2), and the server re-validates on save (`INVALID_RULE_EXPRESSION`).
5. **Sample evaluation (dry-run).** When valid, the builder shows a *sample resolution* (the formula
   rendered with representative sample values per measure) and its *sample result*, so the author sees
   what the expression computes before saving.
6. **Syntax guide.** A collapsible cheat-sheet with click-to-insert **Examples** and four reference
   columns — **Functions**, **Operators** (`> < >= <=`, `== !=`, `AND OR NOT`, `( )`, `%`),
   **Time windows**, and **Sampling** (the live sampling keywords + their labels). Quick-insert buttons
   for every function and every sampling keyword insert the token at the caret.
7. **Sampling control (US-AGG-11).** The sampling keyword the author picks is written **into the
   formula text** (FLAT omitted; the PERIODIC variant explicit) so the expression stays self-describing
   and auditable. PERIODIC is meaningful only for period-capable windows (`m`/`q`/`y`) and is a no-op /
   rejected otherwise (per US-AGG-11 AC7).
8. **Threshold extraction & apply.** For the per-CP threshold editor the builder can list a formula's
   editable comparison constants (`POST /api/v1/formulas/thresholds`) and splice edited values back into
   the original expression, revalidating the result (`POST /api/v1/formulas/apply-thresholds`).

### Not the builder's job
- It does not run real assessments, resolve real measure data, or persist anything — it only
  validates/sample-evaluates and hands the condition text to the covenant save (US-CM-03 AC7).
- It is not shown for MANUAL conditions (US-CM-03, US-CM-22).
- Reporting-calendar and trailing-period **org settings** live on the Settings screen, not the builder
  (US-AGG-11 Lever 2/3); the builder only emits the per-formula sampling keyword.

## Acceptance criteria

### AC1 — Builder appears only for AUTO
```
Given the covenant create/edit form
When Assessment type = Auto
Then the Formula Builder is shown for the condition (autocomplete, highlighting, syntax guide,
     sampling control, live validation, sample evaluation)
When Assessment type = Manual
Then the Formula Builder is hidden and a plain multi-line text box is shown instead (US-CM-03 AC3);
     a CP-scoped custom covenant is always Manual, so it never shows the builder (US-CM-22).
```

### AC2 — Grammar palette comes from the metadata endpoint
```
Given the builder is initialising
Then it loads GET /api/v1/formulas/metadata for the functions, window units and sampling keywords
And it renders the returned functions (AVG, SUM, MIN, MAX, LATEST, CURRENT, COUNT),
    window units (d, w, m, q, y) and sampling keywords (FLAT, PERIODIC, PERIODIC_CURRENT,
    PERIODIC_COMPLETED) as palette/quick-insert controls
And a built-in fallback list is used only until metadata resolves (no hard-coded catalog is the
    source of truth).
```

### AC3 — Measure autocomplete
```
Given I am typing an identifier in an AUTO condition
Then a suggestion list offers matching active measures (code + name + unit/type badge),
     then functions, then logic keywords (AND, OR, NOT, TRUE, FALSE)
And prefix matches rank above substring matches
And I can move the highlight with ArrowUp/ArrowDown, accept with Enter or Tab, dismiss with Escape,
    or click a suggestion to insert it
And accepting a function inserts it with an open parenthesis and places the caret inside.
```

### AC4 — Live syntax highlighting
```
Given a non-empty condition
Then a tokenised preview colours measures, functions, numbers, strings, operators, keywords and
     parentheses distinctly
And any token that is not a known measure/function/operator is shown as "unknown" (red).
```

### AC5 — Live validation with inline error offsets
```
Given an AUTO condition being edited
When I stop typing (debounced)
Then the builder calls POST /api/v1/formulas/validate with requireBoolean = true against the active
     measure catalog
And when invalid it lists each error message and highlights the offending span using the returned
    start/end character offsets
And the covenant cannot be saved while any error is present
And the server independently re-validates on save and rejects an invalid AUTO condition
    (INVALID_RULE_EXPRESSION) (US-CM-03 AC7).
```

### AC6 — Sample evaluation (dry-run) when valid
```
Given a valid AUTO condition
Then the builder shows a "Valid" state with a sample resolution (the formula rendered with
     representative per-measure sample values) and the sample result it computes
And a boolean condition dry-runs to true/false so the author can sanity-check the logic before saving.
```

### AC7 — Syntax guide and quick-insert
```
Given the builder's syntax help is expanded
Then it shows click-to-insert Examples and reference columns for Functions, Operators, Time windows
     and Sampling
And clicking an example, a function button, or a sampling button inserts that token at the caret
And the Sampling reference lists each keyword returned by metadata with its human label.
```

### AC8 — Sampling control writes the keyword into the formula (US-AGG-11)
```
Given I choose a sampling option for a windowed aggregation
Then FLAT ("All readings") is the default and is omitted from the rendered formula
And a PERIODIC choice writes the explicit keyword into the expression text
    (PERIODIC / PERIODIC_CURRENT / PERIODIC_COMPLETED) so the condition is self-describing
And PERIODIC is validated only for period-capable windows m/q/y; a PERIODIC variant with no window
    or a d/w window is rejected (INVALID_WINDOW), and on LATEST/CURRENT it is accepted but ignored
    (US-AGG-11 AC7).
```

### AC9 — Boolean-condition enforcement and type checking
```
Given formula validation with requireBoolean = true
Then a top-level non-boolean expression is rejected (NON_BOOLEAN_CONDITION, e.g. "DSCR" alone)
And unknown measures (UNKNOWN_MEASURE), unknown functions (UNKNOWN_FUNCTION), wrong arity
    (BAD_ARITY), bad argument types (ARG_TYPE), type mismatches (TYPE_MISMATCH_COMPARE /
    _ARITH / _LOGICAL), chained comparisons (CHAINED_COMPARISON) and unknown sampling keywords
    (INVALID_SAMPLING) are each reported with a code and offsets
And AVG/SUM/MIN/MAX require a numeric inner measure while COUNT/LATEST/CURRENT accept any type.
```

### AC10 — Threshold extraction and apply (per-CP override)
> **Amended by [US-CM-25](US-CM-25-per-cp-covenant-definition-and-audit.md):** the onboarding wizard no
> longer drives the per-CP override through these two endpoints — the Configure step now edits the whole
> definition in this builder and sends the expression directly. Both endpoints remain part of this
> builder's API, and `FormulaEngine.thresholds` still powers US-MON-16 headroom; they simply have no
> wizard caller. The MANUAL clause below is unchanged.

```
Given a saved AUTO covenant formula with comparison constants
When the per-CP threshold editor opens
Then POST /api/v1/formulas/thresholds returns each editable literal (value, type, operator,
     tracked-value expression, offsets)
And POST /api/v1/formulas/apply-thresholds splices edited values back into the original expression
    and revalidates it, returning the rebuilt condition and its validation result
And a MANUAL covenant exposes no adjustable thresholds (US-CM-03 "Interaction").
```

### AC11 — Safety limits
```
Given any expression
Then it is rejected if blank (EMPTY_FORMULA), longer than 2000 characters (FORMULA_TOO_LONG),
     nested too deeply (TOO_DEEP), or referencing more than 64 terms (TOO_MANY_TERMS)
And the grammar has no production for method calls, type references, property navigation or bean
    access (no reflection surface — safer than the legacy SpEL validator).
```

### AC12 — Rendering round-trip is stable
```
Given a valid condition
Then FLAT aggregations render without a sampling argument (existing formulas are byte-identical)
And each PERIODIC variant renders its explicit keyword
And parse -> render -> parse is stable (US-AGG-11 AC8).
```

### AC13 — Switching Auto <-> Manual preserves the typed text
```
Given an AUTO condition with text typed in the builder
When I switch to Manual
Then the same text is preserved in the plain text box and is no longer validated (US-CM-03 AC4)
When I switch back to Auto
Then the text is reloaded into the builder and validated, and save is blocked until it is a valid
    boolean formula (US-CM-03 AC5).
```

### AC14 — Backward compatibility
```
Given covenants created before the assessment-type feature
Then they are treated as AUTO and open in the Formula Builder, validating exactly as before
    (US-CM-03 AC8)
And no stored expression is rewritten by this feature.
```

## Data / API notes
- **Backend** — `FormulaEngine` (`@Component`) is the public entry point: `validate(expr, catalog,
  requireBoolean)` (never throws — returns errors with offsets + a sample dry-run), `compile`,
  `evaluate`/`evaluateRaw`, `renderResolved`, `trackedValueExpression`, `thresholds`, `applyThresholds`.
  Bounds: `MAX_LENGTH = 2000`, `MAX_TERMS = 64`, parser nesting depth.
- **Endpoints** — `FormulaController` at `/api/v1/formulas`:
  - `POST /validate` → `ValidateFormulaResponse { valid, errors[{code,message,startOffset,endOffset}],
    sampleResolution, sampleResult }` (request `requireBoolean` defaults to **true**).
  - `GET /metadata` → `FormulaMetadataResponse { functions[], windowUnits[{symbol,label}],
    samplingKeywords[{name,label}] }` (functions from `FunctionKind`, sampling from `SamplingKeyword`).
  - `POST /thresholds` → the editable comparison constants of an expression.
  - `POST /apply-thresholds` → rebuilt condition + revalidation.
- **Grammar enums** — `FunctionKind` (AVG/SUM/MIN/MAX/LATEST/CURRENT/COUNT), `WindowUnit`
  (d/w/m/q/y; `isPeriodCapable()` = m/q/y), `SamplingKeyword` + `SamplingMode`/`PeriodInclusion`,
  `BinaryOperator` (arithmetic/comparison/equality/logical), `FormulaType`
  (NUMBER/STRING/DATE/BOOLEAN), `ErrorCode` (the full set surfaced above).
- **Catalog** — validation runs against `MeasureCatalogService.activeCatalog()`; each `MeasureMeta`
  carries a type and an optional `sampleValue` used for the dry-run. Measures are referenced by
  **code** (columnar `source_column` or KEY_VALUE `source_code`, US-DS-10) — the builder is unchanged
  by the table layout.
- **Frontend** — `FormulaBuilder.tsx` (reusable component; props: measures, metadata, value/onChange,
  result, validating), `FormulaBuilderPage.tsx` (standalone screen, reached via the app's
  "Formula Builder" nav item — view kind `formula-builder`), `components/formula/tokenize.ts`
  (highlighter), hooks `useMeasuresQuery` / `useFormulaMetadataQuery`, `api/formulas.ts`.
  Validation is debounced (~400 ms) and only fires for a non-empty expression.
- **No schema change** — this story specifies existing behaviour; `condition_expression` on
  `cov_covenant_master` stores the authored formula (US-CM-03).

## Interaction with other features
- **US-CM-03 (Assessment type Auto/Manual):** the parent story — the builder is the AUTO editor; MANUAL
  is free text with no builder/validation. AC2/AC3/AC4/AC5/AC7/AC8 there are the origin of AC1, AC5,
  AC9, AC13, AC14 here.
- **US-AGG-11 (period-aware windowed aggregation):** supplies the sampling keywords and the sampling
  control (AC8, AC12); the engine semantics (period selection, fiscal calendar, trailing inclusion) are
  specified there and unchanged here.
- **US-CM-22 (CP-scoped custom covenant):** custom covenants are MANUAL-only, so the builder never
  appears for them (AC1).
- **US-DS-10 (row/key-value data source):** measures are referenced by code regardless of the physical
  table layout; the builder is unaffected.
- **Per-CP threshold override (Epic A/B):** consumes the threshold extract/apply endpoints (AC10);
  only AUTO covenants have adjustable thresholds.
- **US-ASM-05 (manual assessment execution):** the run-time contrast — MANUAL conditions (no builder)
  produce a person-entered result rather than a formula evaluation.

## Dependencies
- Builds on the formula DSL (`formula/**`) and `FormulaEngine` / `FormulaController`.
- Requires the active measure catalog (`MeasureCatalogService`) and the reference-data-driven measure
  list (US-DATA-06 for catalog population).
- Inherits the sampling grammar from US-AGG-11 and the AUTO/MANUAL toggle from US-CM-03.

## Assumptions & open questions (confirm with product)
- **Umbrella/consolidation story.** This documents behaviour already shipped across US-CM-03 / US-AGG-11
  and does not introduce new engine semantics. *Open:* whether product wants US-CM-03's builder ACs
  retired in favour of this consolidated story, or kept as-is with this as the reference.
- **Standalone builder page.** A sandbox `FormulaBuilderPage` exists in addition to the in-form builder.
  *Open:* confirm it should remain a first-class, navigable screen (vs. an in-form-only editor).
- **Debounce / validation cadence** (~400 ms) and the **8-suggestion** autocomplete cap are UI tuning
  choices, not contractual — adjustable without spec change.
- **Threshold editing surface.** AC10 assumes the per-CP override editor is the primary consumer of the
  threshold endpoints; if other screens need it, the ACs generalise unchanged.
