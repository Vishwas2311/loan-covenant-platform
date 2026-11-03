# US-MON-08 — Per-covenant monitoring timeline on the CP screen

**Epic:** Monitoring scheduling & visibility
**Status:** Draft (documents shipped behaviour) · **Priority:** High

## Story
**As a** user monitoring a CP
**I want** each covenant on the CP to show its monitoring frequency, start date (and whether it has
started), the next due date, when it was last assessed, and a derived timing status
**so that** I can immediately see what has started, what is upcoming, and what is overdue — instead
of only a compliance count.

## Description
The CP detail screen (`GET /api/v1/monitoring/cps/{cpId}/covenants`) lists every applied covenant with
**time awareness** plus its latest compliance outcome. Each covenant card shows:

| Element | Meaning |
|---------|---------|
| Frequency | the applied covenant's effective frequency (DAILY … CUSTOM) |
| Start date | chip: **Started {date}** (green, `startDate ≤ today`), **Starts tomorrow** / **Starts in N days** (blue, future) |
| Next due | chip: **Overdue by N days** (red), **Due today** / **Due tomorrow** / **Due in N days** (amber ≤ window), **Due in N days** (blue, beyond), **First due {date}** (grey, not started), or **—** (grey, none) |
| Last assessed | link "{date} · {RESULT}" opening the assessment, or **Never** |
| Timing status | badge: **Not started** · **Up to date** · **Due soon** · **Overdue** · **Ended** |

Cards also carry category (Financial / Non-financial), condition type (precedent/subsequent),
classification (external/internal), a non-ACTIVE status badge when applicable, the effective condition
expression (formula) or manual instruction, and a left accent border coloured by the latest result.

### Derived-field definitions (shared calculator: `MonitoringTimingCalculator`)
The per-CP timeline and the portfolio schedule (US-MON-09) share one definition so "next due",
"overdue" and "due soon" mean the same everywhere.
- **Next due** = the first cron occurrence **strictly after the last assessment** (`latestExecutedAt`);
  when never assessed, the first occurrence **at/after `startDate`**. It is *not* re-based on today.
  Nulled (shown as `—`) once it would fall after the monitoring end date.
- **Started** = `startDate ≤ today`.
- **Ended** = the monitoring end date has passed (`endDate < today`).
- **Overdue** = the covenant has started and `nextDue < today`; `overdueByDays = days(nextDue → today)`.
- **Due soon** = started and `today ≤ nextDue ≤ today + window`, where **window** is the org-configured
  `dueSoonDays` reporting setting (1–365, default **7**), read at request time.
- **Up to date** = started with a future next due beyond the window, or no remaining occurrence.

## Acceptance criteria

### AC1 — Timing shown per covenant
```
Given a CP with applied covenants
When I open the CP detail screen
Then each covenant shows its frequency, start date, next due date, and last assessed (date + result)
And each carries a timing-status badge (Not started / Up to date / Due soon / Overdue / Ended)
And each shows its latest compliance result badge.
```

### AC2 — Start date passed vs upcoming
```
Given one covenant whose start date is in the past and one whose start date is in the future
Then the past one shows a "Started {date}" chip
And the future one shows "Starts tomorrow" or "Starts in N days" and timing status Not started
And a covenant that has not started shows no Overdue state (its next due renders "First due {date}").
```

### AC3 — Next due, due soon and overdue
```
Given a started covenant whose next due date is in the past
Then it shows "Overdue by N days" in red and timing status = Overdue
Given a started covenant whose next due date is within the due-soon window (default 7 days, configurable)
Then it shows "Due in N days" (or Due today / Due tomorrow) in amber and timing status = Due soon
Given a started covenant whose next due date is beyond the window
Then it shows "Due in N days" in blue and timing status = Up to date.
```

### AC4 — Sort & filter (client-side)
```
Given a CP with several covenants
Then I can sort by Next due (soonest/overdue first, default) or by Start date
And I can filter with chips: All · Overdue · Due soon · Not started · Active (each showing a count)
Where Active means applied-covenant status = ACTIVE
And the filters/sort update the list in place without leaving the screen.
```

### AC5 — Last assessed linkage
```
Given a covenant that has been assessed
When I click its "Last assessed" entry
Then I open that assessment's detail (resolved expression, computed value, result).
```

### AC6 — No data / never assessed
```
Given a covenant never assessed
Then "Last assessed" shows "Never" (not clickable)
And next due shows "First due {date}" and the row still shows its start date.
```

### AC7 — Per-covenant actions
```
Given a covenant on the CP screen
Then I can trigger "Run now" (manual assessment) which re-runs and opens the resulting assessment
And I can open "History" to see that covenant's assessment history.
```

## UX notes (design)
- Lead each card with the timing chip set (Start · Next due · Last assessed). Colour language:
  green = on track / started, blue = upcoming, amber = due soon, red = overdue, grey = not started / ended.
- Default sort = Next due ascending, so the most urgent (overdue/past) covenant is on top.
- Summary tiles at the top show Covenants / Compliant / Breached / Overdue counts.
- (Not built) A per-covenant micro-timeline track was considered but is not implemented.

## Data / API notes
- **Endpoint:** `GET /api/v1/monitoring/cps/{cpId}/covenants` → `List<CovenantOnCp>`.
  Handler: `MonitoringController.covenantsForCp` → `MonitoringService.covenantsForCp`
  (results ordered by applied-covenant id; sort/filter are client-side).
- **DTO `CovenantOnCp`** exposes: `appliedCovenantId, covenantMasterId, covenantName, category,
  frequency, startDate, status, nextRunAt, lastRunAt, latestResult, latestExecutedAt,
  latestAssessmentId, nextDueDate, timingStatus, overdueByDays, conditionExpression, conditionType,
  classification, assessmentType`. (`endDate` is used internally for ENDED/`—` logic but is not returned.)
- **Timing** is computed by `MonitoringTimingCalculator.compute(...)` from the effective cron
  (`effectiveCron(frequency, storedCron)` — stored per-CP cron else the frequency's default), the
  start/end dates, the latest assessment's `executedAt`, `today`, the system zone, and the org
  `dueSoonDays` window from `ReportingSettingsService`.
- **Frontend:** `CpDetailPage.tsx` + shared chips in `components/monitoringTiming.tsx`
  (`TimingStatusBadge`, `StartChip`, `NextDueChip`, `lastAssessedText`).
- No new tables; any new table uses the `cov_` prefix.

## Dependencies
- US-FREQ-07 (frequency + start date per covenant). US-MON-09 reuses the same
  `MonitoringTimingCalculator` derived fields at portfolio scale. Due-soon window comes from the
  reporting-settings policy (US-MON-09 / reporting settings).
