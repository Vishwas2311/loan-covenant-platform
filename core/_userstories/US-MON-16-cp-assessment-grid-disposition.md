# US-MON-16 — CP-level covenant assessment grid & Waiver/Extension disposition

**Epic:** F — RM assessment, disposition & evidence
**Status:** Draft · **Priority:** High

## Story
**As a** Relationship Manager reviewing a CP after a breach email
**I want** a grid of every covenant on that CP showing its expression, actual value, headroom and
status, where I can record a disposition on breaches
**so that** I can review compliance and act — acknowledging, waiving, or extending a breach — from one
CP-level screen.

## Description
Selecting a CP in the monitoring workspace (US-MON-15) opens a **CP assessment grid**: one row per
covenant applied to that CP. Each row surfaces what an RM needs to judge compliance — the effective
expression (with the CP-specific applied threshold), the latest actual computed value, the headroom,
and the derived status — plus the read-only timing dates. On a **breached** row the RM may record a
**disposition** (Waiver or Extension); complied rows are locked.

### Status is derived, not re-computed
The covenant status shown here is **derived from the stored assessment result** — the engine semantics
are unchanged (a covenant condition that evaluates **true ⇒ COMPLIANT**). The grid maps
`BREACHED ⇒ Breached` and `COMPLIANT`/`NA ⇒ Complied`. **Waiver** and **Extension** are dispositions
layered on a *Breached* row; recording one does **not** alter `cov_assessment`, the dashboard counts,
or US-MON-14 — it is a separate, in-place disposition record (US-MON-19).

### Headroom
Headroom is the **signed distance from the threshold in the compliant direction**, computed by reusing
the formula engine (no new parsing): for an upper-bound comparison (`<`, `<=`) it is
`threshold − actual`; for a lower-bound comparison (`>`, `>=`) it is `actual − threshold` (positive =
cushion, negative = breach size). It is shown only for a **single numeric comparison**; compound /
`AND` / `OR` / equality / non-numeric conditions show "—".

## Acceptance criteria

### AC1 — Grid of all covenants on the CP
```
Given I open the CP assessment screen for a CP
Then I see one row per covenant applied to that CP with columns:
    Covenant name · Expression · Actual computed value · Headroom · Status ·
    Start date · Next monitoring date · Last assessment date · Attachments.
```

### AC2 — Expression uses the effective per-CP threshold
```
Given a covenant with a per-CP condition/threshold override
Then the Expression column shows the effective condition (per-CP conditionExpression, else the
     master's), rendered with the applied threshold.
```

### AC3 — Actual value from the latest assessment
```
Given a covenant's latest assessment
Then the Actual column shows its computed value (and value type)
And a covenant that has never been assessed shows "—".
```

### AC4 — Headroom, signed, single-comparison only
```
Given a covenant whose condition is a single numeric comparison
Then Headroom shows the signed distance in the compliant direction
    (upper-bound: threshold − actual; lower-bound: actual − threshold)
And for compound / non-numeric / equality conditions Headroom shows "—".
```

### AC5 — Status derived from the stored result (+ any disposition)
```
Given a covenant's latest assessment result
Then Status shows "Complied" when the result is COMPLIANT or NA
And "Breached" when the result is BREACHED
And when a disposition has been recorded on the current breach, Status shows "Waiver" or "Extension"
And the underlying engine semantics and cov_assessment are unchanged.
```

### AC6 — Only Status and Attachments are editable
```
Given the grid is displayed
Then only the Status and Attachments columns are editable
And every other column (Expression, Actual, Headroom, and the dates) is read-only.
```

### AC7 — Complied rows are locked
```
Given a covenant whose latest result is COMPLIANT or NA
Then its Status shows "Complied" and cannot be edited.
```

### AC8 — Breached rows allow Waiver or Extension only
```
Given a covenant whose latest result is BREACHED
When I edit its Status
Then the only options offered are Waiver and Extension.
```

### AC9 — Extension requires an extension date
```
Given I choose Extension for a breached covenant
Then an extension date is mandatory
And saving is blocked with a validation message until a date is provided
And choosing Waiver requires no date.
```

### AC10 — Manually-assessed covenants hide Actual + Headroom
> **Amended by [US-CM-24](US-CM-24-custom-covenant-assessment-type.md) AC9:** the gate is the covenant's
> **assessment type**, not its category. Only an AUTO covenant is machine-evaluated, so only it can have
> an Actual; keying on the category discarded the computed value of an AUTO covenant classified
> `NON_FINANCIAL` — which the authoring screen permits, since Category and Assessment type are chosen
> independently. The `Non-financial` badge still follows the category.

```
Given a covenant whose master assessment type is MANUAL
Then the Actual and Headroom cells are hidden for that row
And its Status is still derived and (if breached) dispositionable
Given a covenant whose master assessment type is AUTO
Then its Actual and Headroom are shown, whatever its category.
```

### AC11 — Saving a disposition persists, refreshes, and is audited
```
Given I set a Waiver or Extension on a breached covenant and save
Then the disposition is persisted per CP + covenant (one current row, updated in place)
And the grid refreshes to show the new status
And the change is recorded in the append-only status audit (US-MON-19).
```

### AC12 — Read-only timing; disposition gated on BREACHED
```
Given the grid rows
Then Start date, Next monitoring date and Last assessment date are read-only (existing timing chips)
And disposition editing is enabled only when the latest result is BREACHED.
```

## Data / API notes
- New read model **`review/CpReviewRow`** (a `dto/`-placed record so it is coverage-excluded) extends
  the existing per-CP covenant fields with: `computedValue`, `computedValueType`, `category`
  (`FINANCIAL`/`NON_FINANCIAL` from `CovenantMaster.getCategory()` — a badge label only),
  `assessmentType` (`AUTO`/`MANUAL`, also from the master — the Actual/Headroom gate), `headroom`, `dispositionStatus`,
  `extensionDate`, `statusSource`, `attachments[]`, plus the read-only `startDate`, `nextDueDate`,
  `lastRunAt`.
- New endpoints on the monitoring controller:
  - `GET /api/v1/monitoring/cp/{cpId}/review` → `CpReviewRow[]`.
  - `PUT /api/v1/monitoring/cp/{cpId}/covenants/{appliedId}/status` → set disposition
    `{ status, extensionDate, note }`; validates `EXTENSION ⇒ extensionDate`; writes the disposition
    and an audit row (US-MON-19).
- New enum **`CovenantDispositionStatus`** = `COMPLIED, BREACHED, WAIVER, EXTENSION`; new entity
  **`CovenantDisposition`** (`cov_covenant_disposition`, one current row per `cpId + appliedCovenantId`,
  updated in place): `cpId, appliedCovenantId, assessmentId, status, extensionDate (nullable),
  statusSource, note (nullable)` + auditable fields.
- Headroom reuses the **public** `FormulaEngine.thresholds(String expression)` + the latest
  `Assessment.computedValue`; no new parser.
- Reuses `MonitoringService`'s latest-assessment/timing helpers, `FormulaText`, `ResultBadge`, and the
  timing chips on the frontend.

## Dependencies
- US-MON-15 — the workspace that drills into this screen.
- US-MON-19 — the disposition provenance + audit trail this screen writes to.
- Reuses US-MON-08 timing, US-FREQ-07 per-CP override, the assessment engine (`computedValue`), and the
  `FormulaEngine.thresholds(...)` API.

## Out of scope (flag for follow-up)
- Changing engine semantics or `cov_assessment` (explicitly unchanged — status is derived).
- Headroom for compound/multi-comparison conditions (single numeric comparison only for now).
- Bulk disposition actions across many covenants at once.
