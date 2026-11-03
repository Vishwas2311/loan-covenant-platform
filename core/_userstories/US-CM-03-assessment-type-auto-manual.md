# US-CM-03 — Assessment type (Auto vs Manual)

**Epic:** Covenant eligibility, classification & assessment type
**Status:** Draft · **Priority:** High

## Story
**As a** Covenant Administrator
**I want** to mark a covenant's assessment type as **Auto** or **Manual**
**so that** quantitative covenants are evaluated automatically by a measure formula, while
qualitative covenants capture a free-text instruction that a person verifies — without the system
trying to validate or compute it.

## Description
A required **Assessment type** field (single choice: `Auto` | `Manual`) is added to the covenant
create/edit screen. It changes how the **condition** field behaves:

- **Auto** — the condition is a **measure formula**. The Formula Builder is shown with live syntax
  validation, the autocomplete, the syntax guide, and sample evaluation (current behaviour). The
  covenant cannot be saved while the formula is invalid. Assessments run automatically (US-ASM-05
  contrasts this with manual).
- **Manual** — the condition is **free text** (e.g. *"Confirm the borrower submitted the board
  resolution within 30 days of drawdown."*). The Formula Builder, measure autocomplete, formula
  validation, and sample evaluation are **hidden / not applied**. Any text is accepted and saved
  as-is. No measures are parsed and `referencedMeasures` is empty.

Switching the toggle swaps the editor. Switching from Auto→Manual keeps the typed text but stops
validating it; Manual→Auto re-enables validation and the text must become a valid formula before
save.

## Acceptance criteria

### AC1 — Field present and required
```
Given the covenant create form
Then I see an "Assessment type" control with options Auto and Manual
And it defaults to Auto
And saving is blocked if no assessment type is selected.
```

### AC2 — Auto enables the formula builder + validation
```
Given Assessment type = Auto
When I edit the condition
Then the Formula Builder (autocomplete, syntax guide, sample evaluation) is shown
And the condition is validated live against the active measures
And I cannot save while the formula is invalid (server also rejects with INVALID_RULE_EXPRESSION).
```

### AC3 — Manual shows free text with no validation
```
Given Assessment type = Manual
When I edit the condition
Then a plain multi-line text box is shown (no formula builder, no measure autocomplete,
     no syntax guide, no sample-evaluation panel)
And I can type any text, including text that is not a valid formula
And the covenant saves successfully with that text stored verbatim
And no measures are recorded for the covenant (referencedMeasures is empty).
```

### AC4 — Switching Auto → Manual
```
Given Assessment type = Auto with a (valid or invalid) formula typed
When I switch to Manual
Then the same text is preserved in the free-text box
And no validation errors are shown
And I can save regardless of whether the text was a valid formula.
```

### AC5 — Switching Manual → Auto
```
Given Assessment type = Manual with arbitrary text
When I switch to Auto
Then the text is loaded into the Formula Builder and validated
And if it is not a valid boolean measure formula, save is blocked with the validation error(s).
```

### AC6 — Persistence and detail view
```
Given a saved covenant
Then its assessment type is persisted
And the detail screen shows the assessment type
And for Manual it labels the condition "Assessment instruction (manual)" and renders the text as-is
And for Auto it renders the formula (with measures), as today.
```

### AC7 — Server-side enforcement
```
Given a create/update request
When assessmentType = AUTO
Then the server validates the condition as a boolean measure formula and stores referencedMeasures
When assessmentType = MANUAL
Then the server stores the condition text without formula validation and with empty referencedMeasures.
```

### AC8 — Backward compatibility
```
Given covenants created before this feature
Then they are treated as assessmentType = AUTO
And continue to validate and evaluate exactly as before.
```

## Interaction with other features
- **Threshold override (per-CP):** thresholds can only be tuned for **Auto** covenants (a formula
  has comparison literals). For **Manual** covenants the threshold editor shows "No adjustable
  thresholds." (already the natural result, since there is no parseable formula).
- **Recommendation matching:** unaffected — matching uses eligibility fields, not assessment type.

## Data / API notes
- Add `assessment_type` (`VARCHAR`, `AUTO`/`MANUAL`, CHECK constraint) to `covenant_master`.
- `condition_expression` is reused for both: a formula (Auto) or instruction text (Manual).
- Validation branch keys off `assessmentType` in `CovenantMasterService` and `AssessmentService`.

## Dependencies
- US-ASM-05 (manual assessment execution) — the run-time half of "Manual".
