# US-ASM-05 — Manual assessment execution

**Epic:** Covenant eligibility, classification & assessment type
**Status:** Draft · **Priority:** Medium

## Story
**As a** Credit Analyst
**I want** covenants marked **Manual** to be assessed by a person — recording a result and a note —
rather than auto-evaluated by a formula
**so that** qualitative covenants (document submission, undertakings, board approvals, etc.) are
tracked and reported alongside automatic ones, without the engine trying to compute them.

## Description
This is the run-time counterpart of US-CM-03. When an assessment is created for an applied covenant
whose master is `MANUAL`:
- The engine does **not** parse a formula, resolve measures, or auto-decide COMPLIANT/BREACHED.
- It creates an assessment in a **`PENDING_MANUAL`** state that carries the covenant's instruction
  text (so the analyst knows what to check).
- A user later records the outcome (`COMPLIANT` / `BREACHED` / `NA`) plus a free-text note (and
  optionally an attachment/evidence reference), which completes the assessment.
- Manual assessments are immutable once completed (consistent with the append-only assessment rule).

`AUTO` covenants are unchanged: the engine resolves measures over the window and decides the result
automatically.

## Acceptance criteria

### AC1 — Manual run creates a pending result
```
Given an applied covenant whose master assessment type = MANUAL
When an assessment is triggered (scheduled or manual)
Then a new assessment is created with result/status = PENDING_MANUAL
And no measures are resolved and no formula is evaluated
And the assessment shows the covenant's instruction text for the analyst.
```

### AC2 — Analyst records the outcome
```
Given a PENDING_MANUAL assessment
When an analyst submits a result (COMPLIANT | BREACHED | NA) with a note
Then the assessment is COMPLETED with that result, the note, the user, and the timestamp
And it appears in monitoring/history like any other completed assessment.
```

### AC3 — Required note on manual completion
```
Given an analyst is completing a manual assessment
When they submit without a note
Then the submission is rejected with a validation message requiring a note (rationale/evidence).
```

### AC4 — Auto covenants unaffected
```
Given an applied covenant whose master assessment type = AUTO
When an assessment is triggered
Then it is evaluated automatically by the measure engine exactly as today (no manual step).
```

### AC5 — Immutability
```
Given a COMPLETED manual assessment
When any process attempts to change its result/note/timestamp
Then the change is rejected (append-only contract) — a re-assessment creates a new row instead.
```

### AC6 — Monitoring & reporting
```
Given a CP with both auto and manual covenants
When I view its monitoring dashboard
Then manual covenants pending input are clearly flagged (e.g. "Pending review")
And completed manual results are counted in compliant/breached totals like auto results.
```

## Data / API notes
- Reuse the `assessment` table; add `PENDING_MANUAL` to the result/status model. The
  manual-completion endpoint records result + note + user.
- `AssessmentService` branches on the (effective) covenant's assessment type: `MANUAL` → create
  pending + skip evaluation; `AUTO` → existing resolve+evaluate path.
- The instruction text is the covenant's `condition_expression` (stored verbatim for Manual).

## Open questions (confirm with product)
- Is `PENDING_MANUAL` a new `status` value, a new `result` value, or both? (Recommend: a `status`
  so existing `result` semantics stay clean.)
- SLA/escalation for un-actioned manual assessments — out of scope here; flag for a follow-up.

## Dependencies
- US-CM-03 (assessment type on the covenant).
