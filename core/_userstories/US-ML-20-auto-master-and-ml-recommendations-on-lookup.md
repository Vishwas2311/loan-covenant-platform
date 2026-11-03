# US-ML-20 — Auto master + ML recommendations on Lookup "Next" (ML may add covenants or override thresholds)

**Epic:** E — ML-assisted configuration
**Status:** Draft · **Priority:** High
**Supersedes:** the manual **"Suggest thresholds (ML)"** button introduced in US-ML-12 (the button is
removed; the ML call becomes an automatic step of the recommendation flow).

## Story
**As a** Relationship Manager onboarding a Credit Proposal (CP)
**I want** the system to fetch master-based recommendations *and then* ML-based recommendations
automatically when I click **Next** on the Lookup screen — where ML may propose **new covenants** or
**override the threshold** of a master-recommended covenant
**so that** the Review & Configure grid already reflects the best combined guidance for this deal, with
full provenance, instead of my having to press a separate button.

## Description
Today the wizard fetches only **master-based** recommendations when the Review & Configure step opens,
and ML thresholds are pulled on demand via a **"Suggest thresholds (ML)"** button (US-ML-12) that only
re-tunes the thresholds of covenants already in the grid.

This story changes the trigger and the scope:

1. **Trigger moves to Lookup → Next.** When the user clicks **Next** on the Lookup step (having resolved
   a valid CP), the system runs a two-stage recommendation pipeline **before** the grid is shown:
   - **Stage 1 — master recommendations:** the existing eligibility match
     (`GET /api/v1/credit-proposals/{cpId}/recommendations`).
   - **Stage 2 — ML recommendations:** the CP details are sent to the ML service, which may
     **(a)** return a **new covenant** not in the master set, or **(b)** return an **overriding
     threshold** for a covenant that the master stage already recommended.
2. **Merge + tag.** The two result sets are merged into the grid rows:
   - a master covenant **not touched** by ML keeps `thresholdSource = MASTER`;
   - a master covenant whose threshold ML **overrides** flips to `thresholdSource = ML` and shows the
     ML value as its effective per-CP threshold;
   - a **brand-new ML covenant** is added as a grid row with `thresholdSource = ML`.
3. **Audit the provenance.** Every ML recommendation is written to the append-only
   `cov_threshold_audit` trail (`RECOMMENDED` event) exactly as in US-ML-13 — including when a master
   recommendation is **replaced/overridden** by ML — and the eventual **applied** decision is audited
   (`APPLIED` event) with the resolved source (`MASTER`/`USER`/`ML`) when the user confirms.
4. **Remove the button.** The **"Suggest thresholds (ML)"** button is removed from the Review &
   Configure toolbar; there is no separate on-demand ML trigger.

The user still stays in control: ML values are pre-populated but remain **editable** (editing flips a
row to `USER`), and covenants — master or ML-added — can be deselected before **Confirm and apply**.

## Acceptance criteria

### AC1 — ML runs automatically on Lookup "Next"
```
Given a CP has been resolved on the Lookup step
When I click "Next"
Then the system first fetches master-based recommendations
And then, in the same flow, sends the CP details to the ML service for ML-based recommendations
And the Review & Configure grid is shown pre-populated with the merged result
And no manual "Suggest thresholds (ML)" action is required.
```

### AC2 — "Suggest thresholds (ML)" button removed
```
Given the Review & Configure grid
Then there is no "Suggest thresholds (ML)" button in the toolbar
And the ML recommendation is not triggered by any button on this screen.
```

### AC3 — CP details are the ML payload
```
Given the automatic ML stage runs
Then the backend is called at POST /api/v1/credit-proposals/{cpId}/ml-threshold-recommendations
And the payload carries the CP details (segment, sector, and the CP attributes) plus the
    master-recommended covenants and their effective thresholds as context
And in dev the request is served by the resilient mock ML stub.
```

### AC4 — ML may override an existing (master) covenant's threshold
```
Given the master stage recommended covenant X with its master threshold
And the ML stage returns a different, valid threshold for covenant X (same measures)
Then covenant X's effective per-CP threshold in the grid becomes the ML value
And its provenance is tagged ML (thresholdSource = ML)
And a RECOMMENDED audit row is written (current -> recommended, confidence, rationale, who, when).
```

### AC5 — ML may add a new covenant not in the master set
```
Given the ML stage returns a covenant Y that the master stage did not recommend
Then covenant Y is added to the grid as a new row, selected by default, with provenance ML
And its threshold is the ML-recommended value
And it is applied like any other selected covenant on Confirm and apply
And a RECOMMENDED audit row is written for it.
```

### AC6 — Unchanged / no-op ML output does not disturb master rows
```
Given ML returns a threshold equal to a covenant's current master threshold, or returns nothing for it
Then that covenant keeps thresholdSource = MASTER and its master threshold
And it is not visually flagged as ML-overridden.
```

### AC7 — Override / replacement is tagged in the audit tables
```
Given a master recommendation for covenant X is overridden/replaced by the ML stage
Then cov_applied_covenant.threshold_source is persisted as ML for X on apply
And cov_threshold_audit records a RECOMMENDED event (from the ML stage) and an APPLIED event
    (source ML, master-value -> applied-value, applied-covenant id, by-whom, when) on confirm
And an ML value the user then manually edits before applying is tagged USER instead (US-ML-13 AC2).
```

### AC8 — User stays in control
```
Given the merged grid is shown
Then every ML-set threshold is still editable (editing flips the row to USER)
And any covenant (master or ML-added) can be deselected before Confirm and apply
And resetting a row returns it to the master default (thresholdSource = MASTER).
```

### AC9 — Resiliency & graceful degradation
```
Given the ML service is slow, erroring, or unavailable when Next is clicked
Then the ML stage times out / retries behind the circuit breaker and falls back to an empty result
And the grid still opens with the master-based recommendations only
And a clear "ML recommendations unavailable" notice is shown
And onboarding continues normally (no master row is lost, no crash).
```

### AC10 — Validation of ML output
```
Given the ML service returns a recommended threshold
Then it is validated as a formula before being applied to the grid (a new ML covenant must resolve to a
    valid rule over known measures; an override must keep the covenant's measures)
And an invalid recommendation is dropped (not shown, not applied) without blocking the rest of the grid.
```

### AC11 — No regression to master recommendations or apply
```
Given the two-stage flow
Then the master recommendation matching (US-REC-04) is unchanged
And the already-applied handling (rows shown as "Already applied", never re-applied) still holds
And the Confirm and apply payload and monitoring-schedule creation are unchanged except for the
    threshold-source tagging above.
```

## UX notes
- The Lookup **Next** button shows a loading state while the master + ML stages run; on ML failure the
  grid still opens (master-only) with the existing gray "ML recommendations unavailable" notice.
- ML-added covenants use a distinct **Source** badge (e.g. "ML recommended") to separate them from
  master "Recommended", "Manual" (from library) and "Custom" rows.
- Each ML-influenced threshold shows the **ML** provenance badge already defined in US-ML-13, with a
  **Reset** affordance back to the master default.
- Because ML is no longer user-initiated, there is no separate "Apply selected" ML modal step required;
  the merged result lands directly in the grid. *(Open question below.)*

## Data / API notes
- **Frontend:** move the ML call out of the toolbar button and into the Lookup → Next transition,
  chained after the master `recommendations` query; merge master + ML into `RowState[]`, adding rows for
  ML-only covenants and flipping `thresholdSource` to `ML` for overridden ones. Remove the
  `Suggest thresholds (ML)` button, its `mlMutation` trigger wiring and `mlEligibleCount` gate (the ML
  review modal may be retained or removed per the open question).
- **Backend:** the existing `POST /api/v1/credit-proposals/{cpId}/ml-threshold-recommendations`
  (`MlThresholdController` → `MlThresholdService`) is reused. To support **new** covenants, the ML
  response must be able to reference a covenant the master stage did not include; `MlThresholdService`
  currently drops any recommendation whose `covenantMasterId` is not in the posted in-scope set
  (`currentByMaster.containsKey(...)`) — that filter must be relaxed to allow ML-introduced covenants
  (validated + audited) while still ignoring unknown/garbage ids.
- **Audit:** unchanged tables — `cov_threshold_audit` (`RECOMMENDED` + `APPLIED`) and
  `cov_applied_covenant.threshold_source` (`MASTER`/`USER`/`ML`) from US-ML-13. Overridden master
  recommendations are tagged `ML` on both the applied row and the audit trail.
- **Resiliency:** the ML call keeps the `mlWebClient` timeout + retry + circuit-breaker + empty-list
  fallback; dev uses the `@Profile("dev")` mock stub.

## Dependencies / related
- Evolves **US-ML-12** (removes the manual button; makes ML automatic and able to add covenants).
- Uses the provenance + audit model from **US-ML-13** unchanged, extending the "override" case.
- Builds on master recommendation matching **US-REC-04** and the Review & Configure grid / apply flow.

## Open questions (confirm before implementation)
1. **Preview vs auto-apply:** should ML overrides/new covenants land **directly** in the grid (this
   draft), or still pass through the US-ML-12 confirmation modal for accept/decline before populating?
   Auto-populate is simpler and matches "system will fetch… on Next"; the modal keeps the human-accept
   step US-ML-12/US-ML-13 emphasise.
2. **New-covenant identity:** can ML only reference **existing** covenant-master ids (safer — it just
   "activates" a library covenant for this CP), or may it mint a genuinely new covenant definition? This
   draft assumes existing master ids (validated) to avoid unsanctioned covenant creation.
3. **Manual covenants:** are ML-added covenants always AUTO (threshold-bearing), or can ML add a MANUAL
   covenant too? This draft assumes AUTO only.
