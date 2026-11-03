# US-MON-19 — Covenant status provenance & disposition audit trail

**Epic:** F — RM assessment, disposition & evidence
**Status:** Draft · **Priority:** High

## Story
**As a** Covenant Administrator / auditor
**I want** every covenant status change tagged with its source and recorded in an append-only trail —
like ML thresholds (US-ML-13)
**so that** we can prove who set each status, when, and why.

## Description
Every disposition recorded on the CP assessment grid (US-MON-16) carries a **status source** and
appends an immutable audit row, mirroring the threshold-provenance model (US-ML-13 / `ThresholdAudit`).
The source is `SYSTEM` (derived from the stored result), `USER` (a person set Waiver/Extension), or
`AI` (an accepted AI suggestion) — analogous to `ThresholdSource` (MASTER/USER/ML). The machine verdict
in `cov_assessment` stays immutable; disposition and its audit are a separate, append-only trail.

## Acceptance criteria

### AC1 — Status source on the disposition
```
Given a covenant's disposition
Then it carries a status source of SYSTEM (derived), USER (person-set), or AI (accepted suggestion)
And this is analogous to the threshold source (MASTER/USER/ML).
```

### AC2 — Append-only audit row per change
```
Given any status/disposition change
Then one cov_covenant_status_audit row is appended with:
    cpId, appliedCovenantId, assessmentId, oldStatus, newStatus, oldExtensionDate, newExtensionDate,
    source, rationale, changedBy, changedAt
And audit rows are never updated or deleted (mirroring ThresholdAudit).
```

### AC3 — Source reflects the actor
```
Given a status change
Then a UI-set Waiver/Extension is recorded with source USER
And an applied AI suggestion is recorded with source AI.
```

### AC4 — Queryable trail
```
Given audit rows exist
Then the trail is queryable per CP and per applied covenant.
```

### AC5 — Assessment immutability preserved
```
Given the disposition/audit trail
Then cov_assessment remains immutable (US-036)
And disposition + status audit are kept separate from the assessment record.
```

### AC6 — cov_ prefix + MSSQL/H2 DDL conventions
```
Given the new tables
Then they use the cov_ prefix and the project's MSSQL/H2 DDL conventions
    (IDENTITY, DATETIME2, named PK/CK, CHECK constraints on the status and source domains).
```

## Data / API notes
- New enum **`CovenantStatusSource`** = `SYSTEM, USER, AI` (analogous to `ThresholdSource`).
- New append-only entity **`CovenantStatusAudit`** (`cov_covenant_status_audit`), mirroring
  `ThresholdAudit` (all-args constructor + getters only, no setters):
  `cpId, appliedCovenantId, assessmentId, oldStatus, newStatus, oldExtensionDate, newExtensionDate,
  source, rationale, changedBy, changedAt`.
- The current-state **`CovenantDisposition`** (`cov_covenant_disposition`, US-MON-16) holds the live
  disposition + its `statusSource`; each write to it appends one `CovenantStatusAudit` row.
- Migrations: `V34__covenant_disposition_and_audit.sql` (disposition + status audit tables) — CHECK
  domains on `status` (`COMPLIED/BREACHED/WAIVER/EXTENSION`) and `source` (`SYSTEM/USER/AI`).
- Follows the existing audit conventions (`cov_threshold_audit`, `cov_applied_covenant_audit`,
  `CurrentUserContext`, `AuditingConfig.SYSTEM_USER` fallback).

## Dependencies
- US-MON-16 (dispositions) and US-MON-18 (AI-sourced changes) — the writers of this trail.
- Aligns with the cross-cutting "Audit & immutability" rule and mirrors US-ML-13.

## Out of scope (flag for follow-up)
- A UI screen to browse the status-audit trail (data is captured + queryable now; a viewer can follow).
- Retention/archival policy for audit rows (append-only; archival is a later concern).
