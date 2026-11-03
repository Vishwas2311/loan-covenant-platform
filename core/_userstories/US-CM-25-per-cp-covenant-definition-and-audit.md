# US-CM-25 — Per-CP covenant definition (full expression) & definition change audit

**Epic:** Covenant eligibility, classification & assessment type
**Status:** Draft · **Priority:** Medium

## Story
**As an** RM / Credit Analyst onboarding a credit proposal (CP)
**I want** to edit a covenant's **whole definition** for this CP in the expression builder — not just its
threshold numbers — and to see **every definition change recorded** against it
**so that** a deal-specific obligation negotiated for this obligor is monitored exactly as agreed,
without authoring a near-duplicate library covenant, and so the change is auditable afterwards.

## Description
The Configure step's **"Thresholds for this CP"** column can only edit the *operands* of the covenant
master's condition — the numeric and boolean literals. The measures, the operators and the logical
structure are fixed to the master.

That is not a UI limitation. It is enforced server-side: a per-CP override whose referenced measures
differ from the master's is rejected with `400 INVALID_THRESHOLD_OVERRIDE` — *"A per-CP override may
only change thresholds, not the measures used"*. This rule was never written down in a story; it is
undocumented shipped behaviour, and this story is where it is documented and removed.

The limit blocks a routine request. A covenant sanctioned in the library as *"DSCR ≥ 1.5"* may be
agreed for **this** CP as *"DSCR ≥ 1.3 **and** ICR ≥ 2.0"*. Today the RM must either author a
near-duplicate library covenant for a single deal, or apply a threshold-only tweak that does not say
what was signed.

This story makes the per-CP condition **fully editable**:

- The column becomes **"Definition for this CP"** and hosts the **Formula Builder** (US-CM-23) —
  measure autocomplete, live validation, syntax guide and sample dry-run — **seeded with the covenant
  master's condition**, so the RM starts from the sanctioned definition and edits it rather than
  facing an empty box.
- The measure-equality guard is **removed**. Any valid boolean measure formula is accepted, whatever
  measures it uses. It is still validated as a boolean formula against the active measure catalog by
  the *same* `CovenantConditionValidator` the covenant authoring screen (US-CM-03) and the custom
  covenant modal (US-CM-24) use, so the three surfaces cannot drift.
- The covenant **master is never touched** — not its condition, not its `referencedMeasures`. The edit
  is stored on the applied covenant for this CP alone, exactly as the threshold override is today.
- Every committed definition change is **audited**, and the trail is surfaced as a read-only
  **Definition change log** on the CP detail screen, per covenant.

**The definition is edited before Apply, not after.** A covenant already applied to the CP stays
read-only — the Configure step already refuses to re-apply it (*"Already applied"*). So in practice the
change log holds **one row per applied covenant**: the master → per-CP change committed at apply time.
It appends another row only if that covenant is expired and later re-applied. Editing a *live*
covenant's definition mid-life is deliberately out of scope (below), and is the natural follow-up that
makes the log multi-row.

**Nothing downstream changes to honour the edit.** Assessment, data pre-staging, the review grid and
ops diagnostics already resolve *the per-CP expression, else the master's* and compile **that**, so a
definition referencing measures the master never used is bound, ingested and evaluated correctly.
`referencedMeasures` on the master stays a description of the *library* covenant, and is only read by
the covenant library screen.

### UX (Onboarding wizard → Configure step)
- The column **"Thresholds for this CP"** is renamed **"Definition for this CP"** and widened.
- For an **AUTO** covenant it renders the **Formula Builder**, pre-filled with the master's condition.
  Its collapsible syntax help starts collapsed so the grid row stays readable.
- **Revert to master** (per row, and the existing reset-all action) restores the master's condition and
  clears the override.
- A **MANUAL** covenant is unchanged: *"Manual covenant — assessed by an analyst (no thresholds)."*
- The existing **Source** badge (`Recommended` / `ML recommended` / `Manual` / `Custom`) and the
  US-ML-13 provenance rule are unchanged: no override ⇒ `MASTER`, the ML expression verbatim ⇒ `ML`,
  any other edit ⇒ `USER`.
- **Apply** stays disabled while any selected covenant's definition is invalid or its validation is in
  flight.

### UX (CP detail → per applied covenant)
A read-only **Definition change log** card under each applied covenant, columns
**When · Who · Old · New**, newest first — the same shape as the covenant master's existing
"Expression change log". A covenant applied without an override shows *"No definition changes
recorded."*

## Acceptance criteria

### AC1 — The Configure step edits the whole definition
```
Given the Configure step of the onboarding wizard
Then the "Thresholds for this CP" column is replaced by "Definition for this CP"
And for an AUTO covenant it is the Formula Builder (measure autocomplete, live validation, syntax
    guide, sample dry-run), seeded with the covenant master's condition
And a MANUAL covenant still shows "Manual covenant — assessed by an analyst (no thresholds)".
```

### AC2 — Any part of the definition may change, including the measures
```
Given a covenant whose master condition is LATEST(DSCR) >= 1.5
When I set its definition for this CP to LATEST(ICR) >= 2.0 AND LATEST(DSCR) >= 1.3
Then the edit is accepted, because it is a valid boolean measure formula
And it is NOT rejected with INVALID_THRESHOLD_OVERRIDE — that guard is removed
And the covenant master's own condition and referencedMeasures are unchanged
And applying the same covenant to another CP is unaffected.
```

### AC3 — Live validation, and the same server-side validation as every other surface
```
Given the per-CP definition editor
Then the definition is validated live against the active measure catalog, requiring a boolean result
And Apply stays disabled while any selected covenant's definition is invalid or validation is in flight
Given an apply request carrying a per-CP definition that is not a valid boolean measure formula
Then it is rejected with 400 INVALID_RULE_EXPRESSION
And no applied covenant, schedule or audit row is created for it.
```
The validation path is the shared `CovenantConditionValidator` already used by the covenant authoring
screen (US-CM-03 AC7) and the custom covenant modal (US-CM-24 AC4), so the surfaces cannot drift.

### AC4 — Leaving the definition as the master's is not an override
```
Given I leave the definition exactly as the master's, or use "Revert to master"
Then no per-CP definition is sent with the apply request
And the applied covenant's conditionExpression stays null, meaning "use the master's"
And its thresholdSource is MASTER
And no definition-change audit row is written.
```

### AC5 — Provenance is unchanged (US-ML-13)
```
Given a definition edited for this CP
Then thresholdSource is ML when it is the ML-recommended expression verbatim
And USER for any other edit
And MASTER when there is no override
And the Source badge in the grid follows it, as today.
```

### AC6 — Every committed definition change is audited
```
Given I apply a covenant with a per-CP definition that differs from the master's
Then one append-only audit row is recorded with cpId, covenantMasterId, appliedCovenantId, source,
     the old expression (the master's), the new expression (the per-CP definition), changedBy and
     changedAt
And audit rows are never updated or deleted
And no row is written when the covenant is applied without an override (AC4).
```

### AC7 — The definition change log is visible per covenant on CP detail
```
Given the CP detail screen
Then each applied covenant shows a read-only "Definition change log" with
     When / Who / Source / Old / New, newest first
And a covenant applied with no override shows "No definition changes recorded."
And existing audit rows recorded before this feature are shown in it unchanged.
```

### AC8 — The edited definition is what gets evaluated
```
Given an applied covenant whose per-CP definition references a measure its master does not
When its monitoring date is assessed
Then the per-CP definition is the expression compiled and evaluated
And the measures it references are resolved and pre-staged for that CP
And the assessment stores the effective expression it evaluated
And the CP assessment grid's Expression column shows that per-CP definition (US-MON-16 AC2).
```

### AC9 — Backward compatibility
```
Given covenants applied before this feature
Then their stored per-CP conditionExpression and thresholdSource are unchanged
And existing threshold-override audit rows render in the new Definition change log
And nothing is backfilled and no migration runs.
```

## Interaction with other features
- **US-CM-23 (Formula Builder):** reused as-is as the per-CP definition editor. Its **AC10 is amended** —
  the wizard no longer drives the per-CP override through `POST /api/v1/formulas/thresholds` +
  `apply-thresholds`. Those endpoints remain part of the builder's API (and `FormulaEngine.thresholds`
  still powers US-MON-16 headroom), but the wizard edits the expression directly.
- **US-CM-24 (custom covenant assessment type):** its **AC8 is amended** — an Auto row offers per-CP
  **definition** editing rather than threshold-only editing; a Manual row is unchanged. The per-CP
  editor also validates through the same `CovenantConditionValidator` this story introduced.
- **US-FREQ-07 (per-CP frequency & overrides):** widens the per-CP override from *thresholds only* to
  the **whole condition**. Per-CP frequency, start date, condition type and classification are
  untouched.
- **US-CM-03 (Assessment type):** unchanged — only AUTO covenants have an editable condition; MANUAL
  covenants show the analyst note instead of an editor.
- **US-ML-12 / US-ML-20 (ML thresholds):** the ML recommendation still seeds the editor and US-ML-13's
  provenance rule is unchanged; the seed now populates the Formula Builder instead of the threshold
  inputs. ML still recommends thresholds only — it does not propose structural edits.
- **US-MON-16 (CP assessment grid):** its AC2 already renders *"the effective condition (per-CP
  conditionExpression, else the master's)"* — no change needed; that expression may now differ
  structurally from the master's.
- **US-MON-19 / US-ML-13 (audit conventions):** the definition log follows the same append-only,
  never-updated audit pattern, reusing `cov_threshold_audit`.
- **US-ASM-05 (manual assessment):** MANUAL covenants are unaffected — no definition editor.

## Data / API notes
- **No schema change, no Flyway migration.**
  `cov_applied_covenant.condition_expression VARCHAR(2000)` already exists and is nullable, and
  `cov_threshold_audit` already carries `applied_covenant_id`, `old_expression` and `new_expression`
  (2000 chars each) and is append-only. Because editing stays at apply time, the existing `APPLIED`
  event is the one recorded, so `ck_threshold_audit_event`'s
  `CHECK (event IN ('recommended','applied'))` does not need widening.
- `AppliedCovenantService.validatedOverride` — **delete** the referenced-measure comparison and its
  `INVALID_THRESHOLD_OVERRIDE` error, and validate through the shared
  `CovenantConditionValidator.validateOrThrow` (the same `INVALID_RULE_EXPRESSION` code it already
  throws, so invalid-formula behaviour is unchanged). Null/blank still means "use the master's".
- The apply-time audit write in `AppliedCovenantService.applyOne` is already correct — it records
  master → per-CP with the resolved `ThresholdSource` — and needs no change beyond its wording.
- **New read endpoint:** `GET /api/v1/applied-covenants/{id}/definition-history` →
  `DefinitionChangeRow[]` (`changedAt`, `changedBy`, `oldExpression`, `newExpression`, `source`),
  served by the existing `ThresholdAuditRepository.findByAppliedCovenantIdOrderByChangedDateDesc` over
  the existing `ix_threshold_audit_applied` index. A `dto/`-placed record, so it is coverage-excluded.
- **Frontend:** the wizard cell renders `FormulaBuilder` in place of `ThresholdEditor`; the row's
  `conditionOverride` / `conditionValid` / `thresholdSource` state and the apply payload are unchanged.
  `ThresholdEditor` then has no remaining caller and is removed with its test. The cell is a thin
  `DefinitionEditor` around `FormulaBuilder` that seeds itself from the master — the wizard row does not
  carry the master's expression — so the existing `resetKey` remount is what "Revert to master"
  reseeds through.

## Dependencies
- US-FREQ-07 (the per-CP override being widened), US-CM-23 (Formula Builder), US-CM-24
  (`CovenantConditionValidator`), US-ML-13 (threshold provenance + audit conventions),
  US-MON-16 (effective-expression rendering).

## Out of scope
- **Editing a covenant's definition after it is applied** — the log therefore holds one row per applied
  covenant. Mid-life renegotiation (a `PATCH` carrying `conditionExpression`, an edit affordance on the
  CP screens, and a new `EDITED` audit event, which *would* need a migration to widen
  `ck_threshold_audit_event`) is the natural follow-up.
- Promoting a per-CP definition back into the covenant master / shared library.
- A per-CP instruction override for MANUAL covenants.
- A side-by-side diff of the per-CP definition against the master beyond the existing Source badge.
- Retaining the guided numeric threshold inputs as an alternative mode — the expression builder
  replaces them.
- Extending ML recommendation to propose structural (measure/operator) edits rather than thresholds.
