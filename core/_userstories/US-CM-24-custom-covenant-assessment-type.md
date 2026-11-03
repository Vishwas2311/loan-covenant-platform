# US-CM-24 — Assessment type (Auto / Manual) for a CP-scoped custom covenant

**Epic:** Covenant eligibility, classification & assessment type
**Status:** Draft · **Priority:** Medium

## Story
**As an** RM / Credit Analyst onboarding a credit proposal (CP)
**I want** to choose whether a one-off **custom covenant** is assessed **automatically** from a measure
formula or **manually** by an analyst
**so that** a CP-specific quantitative threshold (e.g. *"this obligor's DSCR must stay above 1.4"*) is
machine-evaluated and monitored like any library covenant, without publishing a deal-specific covenant
into the shared library.

## Description
US-CM-22 introduced the CP-scoped custom covenant but hardcoded it to **Manual**: the Definition was
always free text, `referencedMeasures` was always empty, and no formula was ever validated or evaluated.
A user needing a bespoke *formula-driven* covenant for one deal had only bad options — pollute the shared
covenant library with a CP-specific covenant, or accept one that nobody evaluates automatically.

This story adds a required **Assessment type** (`Auto` | `Manual`) to the **Add custom covenant** modal
and makes it behave exactly like the covenant authoring screen (US-CM-03):

- **Auto** — the Definition is a **measure formula**, authored in the **Formula Builder** (US-CM-23) with
  measure autocomplete, live validation and sample dry-run. Submit is blocked while the formula is
  invalid; the server re-validates it and extracts `referencedMeasures`. The covenant is then evaluated
  automatically by the assessment engine, like any library Auto covenant.
- **Manual** — unchanged from US-CM-22: a free-text instruction, stored verbatim, never validated as a
  formula, `referencedMeasures` empty, assessed by a person (US-ASM-05).

The control **defaults to Auto**, mirroring the authoring screen. Switching it swaps the editor.

**Category is derived from the assessment type** — `Auto → Financial`, `Manual → Non-financial` — and is
not asked for in the modal, consistent with US-CM-22 deriving every field the modal does not ask for. It
is a classification label: an Auto custom covenant is a quantitative threshold, so *Financial* is the
honest label for it.

**And the assessment grid's Actual/Headroom gate moves onto the assessment type.** US-MON-16 AC10 keyed
those two cells off the *category*, which is why US-CM-22's hardcoded `Non-financial` hid the computed
value of a covenant the engine had just evaluated. Deriving the category fixes that for custom covenants
but leaves the same trap on the library screen, where Category and Assessment type are chosen
independently — an author can still save an Auto covenant as *Non-financial* and silently lose its
Actual. So the gate itself changes: **only an AUTO covenant is machine-evaluated, so only it can have an
Actual to show — whatever its category** (AC9). A MANUAL covenant is verified by a person and has no
computed value in the first place. The `Non-financial` badge continues to follow the category.

Everything else about US-CM-22 is untouched: the covenant is still `isCustom = true` with
`ownerCpId = {cpId}`, eligibility is still copied from the CP's own attributes, the per-CP
duplicate-name guard still applies, cron is still validated, and the apply-time values (start date,
condition type, classification) still ride along into the Apply step rather than onto the master.

### UX (Onboarding wizard → "Add custom covenant" modal)
The fields are as US-CM-22, plus:
- **Assessment type** — required single choice `Auto` | `Manual`, **defaults to Auto**; the same
  segmented control and explanatory helper text as the covenant authoring screen.
- **Definition** — swaps on the type:
  - **Auto** → the **Formula Builder** ("Covenant condition"): a measure formula that is TRUE when the
    covenant is satisfied, validated live against the active measure catalog.
  - **Manual** → today's plain multi-line text box (free-text instruction, ≤ 2000 chars).

Submit gating extends US-CM-22's rule: for **Manual**, any non-empty definition (plus the other required
fields); for **Auto**, the live validation must additionally have returned *valid* and not be in flight.

The resulting grid row follows the type: an **Auto** custom row offers **per-CP threshold editing** just
like a library Auto covenant, while a **Manual** row keeps the *"Manual covenant — assessed by an analyst
(no thresholds)"* note.

## Acceptance criteria

### AC1 — Assessment type control
```
Given the "Add custom covenant" modal
Then I see a required "Assessment type" control with options Auto and Manual
And it defaults to Auto.
```

### AC2 — Auto authors the condition in the Formula Builder
```
Given Assessment type = Auto
Then the Definition field is replaced by the Formula Builder (measure autocomplete, live validation,
     syntax guide, sample dry-run)
And the condition is validated live against the active measures
And submit stays disabled while the formula is invalid or validation is in flight
And submit becomes enabled once the formula is valid and the other required fields are set.
```

### AC3 — Manual keeps today's free-text behaviour
```
Given Assessment type = Manual
Then the Definition field is a plain multi-line text box, with no formula builder and no validation
And I can type any text, including text that is not a valid formula
And submit is allowed on any non-empty definition (US-CM-22's rules unchanged).
```

### AC4 — Server-side validation branches on the type
```
Given a create-custom-covenant request with assessmentType = AUTO
When the definition is not a valid boolean measure formula
Then the request is rejected with 400 INVALID_RULE_EXPRESSION
And neither a covenant master nor a recommendation row is created
Given assessmentType = MANUAL
Then the definition is stored verbatim with no formula validation.
```
The validation path and error code are the *same* as the covenant authoring screen (US-CM-03 AC7), so the
two screens cannot drift.

### AC5 — Referenced measures
```
Given assessmentType = AUTO
Then the created covenant's referencedMeasures are the measures parsed from the formula
Given assessmentType = MANUAL
Then referencedMeasures is empty.
```

### AC6 — Category is derived from the assessment type
```
Given assessmentType = AUTO
Then the created covenant's category is Financial
Given assessmentType = MANUAL
Then the created covenant's category is Non-financial
And the modal never asks for a category.
```

### AC7 — Assessment type is required
```
Given a create-custom-covenant request with no assessmentType
Then the request is rejected with 400 (bean validation)
And custom covenants created before this feature keep their stored MANUAL type (nothing is backfilled).
```

### AC8 — Thresholds on the wizard grid follow the type
> **Amended by [US-CM-25](US-CM-25-per-cp-covenant-definition-and-audit.md):** an Auto row offers per-CP
> **definition** editing (the whole condition, in the Formula Builder) rather than threshold-only
> editing, validated through the same `CovenantConditionValidator` as AC4. A Manual row is unchanged.

```
Given an Auto custom covenant was added
Then its wizard row offers per-CP threshold editing, like a library Auto covenant
Given a Manual custom covenant was added
Then its row shows "Manual covenant — assessed by an analyst (no thresholds)".
```

### AC9 — Downstream assessment, and Actual/Headroom follow the assessment type
```
Given an applied Auto custom covenant
When its monitoring date is assessed
Then it is evaluated automatically (formula compiled, measures resolved, status computed),
     exactly like a library Auto covenant
And its Actual and Headroom are shown on the CP assessment grid
Given an applied Manual custom covenant
Then it still produces a pending-manual assessment for a person to complete (US-ASM-05)
And its Actual and Headroom cells show "—".
```
The grid gates those two cells on the **assessment type**, superseding US-MON-16 AC10's category gate, so
the rule holds for library covenants too:
```
Given any applied covenant whose assessment type is AUTO
Then its Actual and Headroom are shown, even if its category is Non-financial
Given any applied covenant whose assessment type is MANUAL
Then its Actual and Headroom are hidden, even if its category is Financial
And its Status is still derived and (if breached) dispositionable
And the "Non-financial" badge still follows the category, not the type.
```
This is enforced server-side in `ReviewService` (which blanks `computedValue`, `computedValueType` and
`headroom` for MANUAL) as well as in the grid cells, so the two cannot drift.

## Interaction with other features
- **US-CM-22 (CP-scoped custom covenant):** this story supersedes its "always MANUAL / always
  Non-financial / empty referencedMeasures" rule (its AC3). Every other aspect — CP scoping, eligibility
  copy, duplicate-name guard, cron validation, apply-time attributes — is unchanged.
- **US-CM-03 (Assessment type):** the Auto/Manual semantics being mirrored, including the server-side
  validation branch and `INVALID_RULE_EXPRESSION`.
- **US-CM-23 (Formula Builder):** the Auto authoring surface, reused as-is.
- **US-ASM-05 (Manual assessment execution):** still the runtime for Manual custom covenants.
- **US-MON-16 (CP assessment grid):** its **AC10 is amended** — Actual/Headroom render for AUTO covenants
  rather than for Financial ones (AC9). Applies to library covenants as well as custom ones, so an Auto
  covenant classified Non-financial no longer loses the value the engine computed for it. The
  `Non-financial` badge still follows the category.
- **US-ML-12 / US-ML-20 (ML thresholds):** ML logic still skips `isCustom` covenants; Auto custom
  covenants are **not** brought into ML threshold recommendation by this story.

## Data / API notes
- **No schema change.** `cov_covenant_master.assessment_type` already exists with
  `CONSTRAINT ck_covenant_assessment_type CHECK (assessment_type IN ('auto','manual'))`, and a custom
  covenant is an ordinary `cov_covenant_master` row (`is_custom = 1`, `owner_cp_id`). No Flyway migration.
- **Request body** `CreateCustomCovenantRequest` gains `assessmentType` (`@NotNull`, `AUTO`|`MANUAL`)
  alongside `name`, `frequency`, `cronExpression`, `definition`.
- `RecommendationService.createCustomForCp` branches on the type for `category`, `assessmentType` and
  `referencedMeasures`, and validates the formula for `AUTO` before anything is persisted.
  `condition_expression` and `description` are both still populated from the single Definition field.
- Formula validation + measure extraction live in a shared `CovenantConditionValidator` used by both
  `CovenantMasterService` and `RecommendationService`, so both screens behave identically.
- `CpReviewRow` gains `assessmentType` (read from the master alongside `category`, no extra query).
  `ReviewService` gates `computedValue`/`computedValueType`/`headroom` on it instead of the category; a
  row whose master cannot be loaded is treated as AUTO so nothing computed is lost. No schema change.

## Dependencies
- US-CM-22 (the custom covenant flow being extended), US-CM-03 (Auto/Manual semantics),
  US-CM-23 (Formula Builder), US-ASM-05 (manual assessment execution), US-MON-16 (assessment grid
  Actual/Headroom).

## Out of scope
- Creating custom covenants outside the onboarding wizard.
- Promoting a custom covenant into the shared library.
- A user-overridable **Category** — it is derived from the type; surfacing the authoring screen's Category
  select later is additive.
- A separate **Description** field — the single Definition field keeps serving as both description and
  condition, as today.
- Bringing Auto custom covenants into ML threshold recommendation.
