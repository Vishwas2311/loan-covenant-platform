# US-ML-12 — ML-recommended thresholds in CP Onboarding

**Epic:** E — ML-assisted configuration
**Status:** Draft · **Priority:** High

## Story
**As a** Relationship Manager onboarding a Credit Proposal (CP)
**I want** to ask an ML service to recommend covenant thresholds for this CP and review them before
they apply
**so that** thresholds reflect data-driven guidance for this deal, while I stay in control of what is
actually committed.

## Description
In the wizard's **Review & Configure** grid, add a **"Suggest thresholds (ML)"** action. It calls a
(mock, in dev) ML service with the CP's details and the currently-shown covenants + their thresholds,
and returns recommended thresholds. Recommendations are **previewed in a confirmation modal** and only
override the grid where the user accepts them; **unchanged** recommendations are never shown or
applied. Scope is the covenants being onboarded now — **selected, non-manual, not-yet-applied** rows;
already-applied covenants are sent only as read-only context. Manual (analyst-assessed) covenants have
no thresholds and are excluded.

## Acceptance criteria

### AC1 — Action present and correctly scoped
```
Given the Review & Configure grid with at least one selected, non-manual, not-yet-applied covenant
Then a "Suggest thresholds (ML)" button is shown in the top-right toolbar (with Add custom / Add from library)
And triggering it requests recommendations only for selected, non-manual, not-yet-applied covenants
And already-applied and manual covenants are excluded from the request.
```

### AC2 — Mock ML call with the specified payload
```
Given I trigger "Suggest thresholds (ML)"
Then the backend is called at POST /api/v1/credit-proposals/{cpId}/ml-threshold-recommendations
And the payload carries the CP details and, per in-scope covenant, its current effective threshold
    (the per-CP override if set, otherwise the covenant master's condition)
And in dev the request is served by a mock ML stub that returns deterministic recommendations.
```

### AC3 — Only changed thresholds are surfaced/updated
```
Given the ML service returns a recommendation equal to a covenant's current threshold
Then that covenant is not listed for review and its threshold is left unchanged
And only covenants whose recommended threshold differs from the current one are offered for update.
```

### AC4 — Confirmation before applying
```
Given the ML service returns one or more changed recommendations
Then a confirmation modal lists each affected covenant with its current value, the ML-recommended value,
    and a confidence/rationale
And each row has an accept toggle (default accepted)
And nothing changes in the grid until I choose "Apply selected"
And "Cancel" discards all recommendations without changing any threshold.
```

### AC5 — Applying updates only accepted rows
```
Given I accept a subset of recommendations and choose "Apply selected"
Then only the accepted covenants' thresholds are overridden with the ML values in the grid
And the applied ML value remains editable (I can fine-tune it before final apply)
And rejected/declined recommendations leave their thresholds untouched.
```

### AC6 — Resiliency & graceful degradation
```
Given the ML service is slow, erroring, or unavailable
Then the call times out / retries and the circuit breaker prevents repeated stalls
And the feature degrades gracefully: no thresholds change, a clear "ML recommendations unavailable"
    notice is shown, and onboarding continues normally.
```

### AC7 — Validation of ML output
```
Given the ML service returns a recommended threshold
Then it is validated as a formula before being offered (same measures as the master; only threshold
    values may change)
And an invalid recommendation is not applied and does not block the covenant's manual configuration.
```

## UX notes
- Place the button beside *Add custom covenant* / *Add from library*; show a loading state while the
  ML call is in flight.
- In the modal, render current → recommended per threshold slot (e.g. `DSCR >= 1.25 → 1.31`) with the
  confidence and a one-line rationale, so the decision is explainable.
- After applying, show the provenance of each row (see US-ML-13) so the user can see which thresholds
  came from ML.

## Data / API notes
- New endpoint `POST /api/v1/credit-proposals/{cpId}/ml-threshold-recommendations` → list of
  `{ covenantMasterId, recommendedExpression, confidence, rationale }` (changed-only).
- Backend calls a resilient `mlWebClient` (timeout + retry + circuit breaker + empty-list fallback);
  dev target is an in-app `@Profile("dev")` stub reusing the formula engine's
  `thresholds`/`applyThresholds`. No new persistent table for this story (audit is US-ML-13). The
  apply request gains an optional `thresholdSource` (US-ML-13).

## Dependencies / related
- Builds on the Review & Configure grid + `ThresholdEditor` per-CP override.
- Provenance + audit are specified in **US-ML-13**.
