# US-ML-13 — Threshold provenance & recommendation audit trail

**Epic:** E — ML-assisted configuration
**Status:** Draft · **Priority:** High

## Story
**As a** Covenant Administrator / Compliance Officer
**I want** every applied threshold to record *how it was set* (Master default, User, or ML) and a full
audit of ML recommendations
**so that** we can demonstrate, for audit and model-governance, how each covenant threshold on a CP
was arrived at — including ML suggestions that were declined.

## Description
Each applied covenant carries a **threshold source** — `MASTER` (unchanged from the library default),
`USER` (a person edited it), or `ML` (an ML recommendation was accepted). In addition, **every** ML
recommendation event is logged append-only (current → recommended, confidence, rationale, who, when),
whether or not the user accepts it, and the **applied** decision is logged when covenants are
committed.

## Acceptance criteria

### AC1 — Provenance tag on the applied covenant
```
Given a covenant is applied to a CP
Then its threshold source is persisted as MASTER, USER, or ML
And MASTER when no per-CP override is set
And USER when the threshold was manually edited
And ML when an ML recommendation was accepted (and not subsequently edited)
And the applied-covenant API returns the threshold source.
```

### AC2 — Provenance follows the last human/ML action
```
Given an ML recommendation was accepted for a covenant
When I then manually edit that threshold before applying
Then the source becomes USER
And resetting the threshold to the recommended/master default returns the source to MASTER.
```

### AC3 — Recommendation events are audited (including declined)
```
Given the ML service returns recommendations for a CP
Then each recommendation is written to an append-only audit log with:
    cp id, covenant, current value, recommended value, confidence, rationale, requested-by, requested-at
And this happens even for recommendations the user later rejects or ignores.
```

### AC4 — Applied decisions are audited
```
Given I apply covenants with overridden thresholds
Then an APPLIED audit entry is written per overridden covenant capturing
    source (USER/ML), old (master) value, new (applied) value, applied-covenant id, by-whom, when.
```

### AC5 — Immutability & attribution
```
Given audit entries exist
Then they are append-only (never updated or deleted)
And the acting user is captured from the request context (falling back to the system user).
```

### AC6 — Backward compatibility
```
Given applied covenants created before this feature
Then they default to threshold source MASTER
And loading, editing, assessment, and monitoring continue to work unchanged.
```

## Data / API notes
- `cov_applied_covenant.threshold_source` (`VARCHAR(16)` NOT NULL, CHECK in `MASTER/USER/ML`);
  returned on `AppliedCovenantResponse`; set from `ApplyCovenantRequest.thresholdSource` at apply
  time.
- New append-only table `cov_threshold_audit` (`RECOMMENDED` + `APPLIED` events, old/new expression,
  confidence, rationale, by-whom/when) — see migration `V32`.
- Follows the existing audit conventions (`cov_applied_covenant_audit`, `cov_expression_change_log`,
  `CurrentUserContext`).

## Dependencies / related
- Depends on the ML recommendation flow in **US-ML-12**.
- Aligns with the cross-cutting "Audit & immutability" rule in this README.
