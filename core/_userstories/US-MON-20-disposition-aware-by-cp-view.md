# US-MON-20 — By-CP view surfaces handled (Waived/Extended) breaches

**Epic:** F — RM assessment, disposition & evidence
**Status:** Draft · **Priority:** High

## Story
**As a** Relationship Manager scanning the By-CP monitoring view
**I want** each CP row to show how many of its breaches I have already handled (Waived/Extended)
**so that** after I record a disposition the portfolio view reflects my action, and I can tell at a
glance which CPs still have *open* breaches to work versus breaches that are already dealt with.

## Description
The monitoring workspace By-CP view (`MonitoringDashboard`, `GET /api/v1/monitoring/summary`) today
shows pure status counts per CP — Applied / Compliant / Breached / Error / Pending — computed only
from each covenant's latest **assessment result**. Dispositions (Waiver/Extension), which live in a
separate overlay (`cov_covenant_disposition`, US-MON-16/19) and never touch `cov_assessment`, are
invisible here. So an RM who breaches → then Waives every breach on a CP still sees "Breached 4" on
the By-CP row exactly as before, with no signal that the work is done. The drilldown grid
(`CpAssessmentPage`) shows the disposition, but the portfolio roll-up does not, which reads as "my
change didn't save".

This story makes the By-CP roll-up **disposition-aware without hiding breaches**. The `Breached`
count stays exactly as it is (still derived from `cov_assessment` — the daily run and US-MON-14 email
are untouched). We **add** a `dispositioned` ("handled") count so a row can read
**"Breached 4 (2 handled)"**: 4 covenants are breached by result, 2 of those breaches carry an active
Waiver/Extension. This keeps the breach signal honest for risk while giving the RM feedback that their
disposition landed.

### What counts as "handled"
A breach is **handled** when it has an **active disposition** — the same rule the grid already uses
(`dispositionAddressesCurrentBreach`): a `cov_covenant_disposition` row exists for the CP+covenant,
its `assessmentId` equals the covenant's **latest** assessment id, and its status is `WAIVER` or
`EXTENSION`. A disposition recorded against an older assessment (a breach that has since been
re-assessed) is **lapsed** and does **not** count — the covenant is back to an open breach.

## Acceptance criteria

### AC1 — Handled count added alongside the unchanged breached count
```
Given a CP with 4 breached covenants, 2 of which carry an active Waiver/Extension
When I view the By-CP monitoring view
Then the CP row still shows Breached = 4
And the row also shows a "handled" (dispositioned) count of 2
And it reads like "Breached 4 (2 handled)".
```

### AC2 — Recording a disposition increases the handled count
```
Given a breached covenant on a CP with no disposition yet
When I record a Waiver or Extension on it (US-MON-16) and return to the By-CP view
Then that CP's Breached count is unchanged
And its handled count has increased by 1.
```

### AC3 — Only an active disposition counts
```
Given a covenant with a Waiver recorded against an earlier assessment
And the covenant has since been re-assessed and is breached again
When I view the By-CP view
Then the stale Waiver does NOT count toward the handled count
And the covenant is reflected as an open (unhandled) breach.
```

### AC4 — Fully-handled CP is visually distinguishable
```
Given a CP whose every breach carries an active Waiver/Extension (handled == breached, both > 0)
When I view the By-CP view
Then the row indicates all breaches are handled (e.g. "Breached 4 (all handled)" or a muted/neutral
    treatment rather than an alarming one)
And a CP with at least one open breach (handled < breached) remains visually flagged.
```

### AC5 — No dispositions changes nothing
```
Given a CP with breaches but no dispositions recorded
When I view the By-CP view
Then the row is identical to today (handled = 0, breached unchanged).
```

## Data / API notes
- Extend the By-CP summary domain record (`CpMonitoringSummary`) with a `dispositioned` count (or,
  if product wants the split, `waived` + `extended`). Existing count fields are unchanged.
- `MonitoringService.accumulate(...)` / `summary(user)` / `summaryFor(cpId)` inject
  `CovenantDispositionRepository` and, per CP, **batch-fetch** dispositions via `findByCpId(cpId)` into
  a map keyed by `appliedCovenantId` (avoid an N+1 across the portfolio). For each breached covenant,
  increment `dispositioned` when the disposition is **active** using the existing
  `dispositionAddressesCurrentBreach(disp, latestAssessmentId)` rule (factor it out of `ReviewService`
  into a shared helper so both call sites share one definition).
- Endpoint path unchanged: `GET /api/v1/monitoring/summary` — response gains the new field only.
- Frontend: `MonitoringDashboard.tsx` renders the handled count in the Breached cell; extend the
  `CpMonitoringSummary` type in `types/onboarding.ts`.
- **`cov_assessment` is not touched**; the daily breach run and US-MON-14 email are unchanged.

## Dependencies
- US-MON-15 — the workspace that hosts the By-CP view.
- US-MON-16 / US-MON-19 — the disposition record + active-disposition rule this count reads.
- Ships with **US-MON-22** (cache invalidation) so the count refreshes live after a disposition.

## Out of scope (flag for follow-up)
- Changing the US-MON-14 daily breach email body/counts.
- **Hiding** or removing breaches from the count once handled (explicitly rejected — the breach
  signal stays for risk; we annotate, not suppress).
- Making non-BREACHED (e.g. overdue/upcoming) covenants dispositionable (see US-MON-21 out-of-scope).
