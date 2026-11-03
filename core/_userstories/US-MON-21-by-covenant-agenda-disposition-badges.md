# US-MON-21 — By-Covenant agenda badges dispositioned events

**Epic:** F — RM assessment, disposition & evidence
**Status:** Draft · **Priority:** Medium

## Story
**As a** Relationship Manager working the By-Covenant agenda/calendar
**I want** each covenant event to show when its breach has already been Waived/Extended
**so that** I can see which items on my agenda are already handled without opening every CP, while the
due dates I plan around stay exactly where they are.

## Description
The By-Covenant view (`MonitoringSchedulePage`, `GET /api/v1/monitoring/schedule`) shows a
time-ordered agenda of covenant monitoring events bucketed **Overdue / Due soon / Upcoming** purely
from each event's `timingStatus` (computed by `MonitoringTimingCalculator` from cron + start/end +
last-assessed). Like the By-CP view, it is blind to dispositions: an RM who Waives a breached covenant
still sees the event exactly as before, with no "handled" signal.

This story carries the covenant's **effective disposition status** onto each `MonitoringEvent` and
renders a small **Waiver / Extension badge** on handled rows in the agenda and calendar. Crucially,
the **timing bucket is unchanged** — a disposition is a compliance outcome, not a reschedule; a Waived
covenant that is still overdue stays in **Overdue** (recording a Waiver/Extension does *not* move it
to Upcoming or change its next-due date). Optionally, a **"Hide handled"** filter lets the RM collapse
already-dispositioned events to focus on open work.

### Badge reflects an active disposition only
The badge uses the same **active-disposition** rule as US-MON-16/20: the disposition must be for the
covenant's **latest** assessment and be `WAIVER`/`EXTENSION`. A stale/lapsed disposition shows no
badge (the event is treated as un-handled).

## Acceptance criteria

### AC1 — Handled events show a disposition badge
```
Given a covenant event whose latest breach carries an active Waiver or Extension
When I view the By-Covenant agenda (or calendar)
Then that event shows a "Waiver" or "Extension" badge
And an Extension badge shows its extension date.
```

### AC2 — Disposition does not change the timing bucket
```
Given an OVERDUE covenant event that I then Waive
When I return to the By-Covenant view
Then the event is still in the Overdue bucket (its timingStatus and next-due date are unchanged)
And only a Waiver badge has been added.
```

### AC3 — Only an active disposition badges
```
Given a Waiver recorded against an earlier assessment, with a newer assessment since
When I view the By-Covenant view
Then no disposition badge is shown for that event (it reads as un-handled).
```

### AC4 — Optional "Hide handled" filter
```
Given the By-Covenant view has events, some handled and some open
When I enable "Hide handled"
Then events carrying an active Waiver/Extension are hidden
And disabling the filter shows them again
And the default state shows all events (nothing hidden).
```

### AC5 — No dispositions changes nothing
```
Given a set of covenant events with no dispositions recorded
When I view the By-Covenant view
Then it is identical to today (no badges, all events shown, buckets unchanged).
```

## Data / API notes
- Extend the `MonitoringEvent` domain record with the effective `dispositionStatus` (and
  `extensionDate` when EXTENSION) — nullable/absent when there is no active disposition.
- `MonitoringService.schedule(...)` populates it: for each event's applied covenant, look up its
  disposition (batch-fetch per CP as in US-MON-20) and apply the shared
  `dispositionAddressesCurrentBreach` rule. **`timingStatus` computation is unchanged.**
- Endpoint path unchanged: `GET /api/v1/monitoring/schedule` — response gains the new field(s) only.
- Frontend: `MonitoringSchedulePage.tsx` — `EventRow` renders the badge (reuse
  `DispositionStatusBadge` from `components/dispositionStatus`), plus the "Hide handled" toggle;
  extend the `MonitoringEvent` type in `types/onboarding.ts`.

## Dependencies
- US-MON-15 — the workspace that hosts the By-Covenant view.
- US-MON-16 / US-MON-19 — the disposition record + active-disposition rule this badge reads.
- Shares the batched disposition lookup + shared active-disposition helper introduced in US-MON-20.
- Ships with **US-MON-22** (cache invalidation) so badges appear live after a disposition.

## Out of scope (flag for follow-up)
- Using the **Extension date to reschedule** the next monitoring/assessment date — a disposition does
  not move timing here; deriving a new due date from an Extension is a separate change.
- Making non-BREACHED (overdue/upcoming) covenants dispositionable — disposition remains gated to
  `BREACHED` (Gap 3); this story only *displays* dispositions on the agenda.
