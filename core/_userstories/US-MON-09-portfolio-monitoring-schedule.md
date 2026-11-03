# US-MON-09 — Portfolio monitoring schedule & calendar (across CPs)

**Epic:** Monitoring scheduling & visibility
**Status:** Draft · **Priority:** High

## Story
**As a** user responsible for monitoring many CPs (e.g. 10 CPs, ~5 covenants each, all with their
own frequencies and start dates)
**I want** a single time-ordered view of what is **overdue, due soon, upcoming, and starting soon**
across all my CPs
**so that** I can plan my day around monitoring obligations and never miss a covenant date — rather
than opening each CP one by one to discover what is due.

## Problem with today's screen
The current Monitoring dashboard shows, per CP, only **counts by status** (applied / compliant /
breached / error / pending). It answers *"how compliant is this CP?"* but not *"what do I need to do
and when?"* — there is no visibility of start dates or upcoming/overdue monitoring dates across the
portfolio. With dozens of covenants on different cadences, the user is forced to hunt.

## Solution (design)
Add a **Monitoring Schedule** experience (a new tab on the Monitoring area, or a new nav item)
that aggregates monitoring events across all the user's CPs. Two complementary, toggleable views,
with **Agenda as the default** (it's the "what must I act on" workhorse):

### 1. Summary bar (clickable filters)
A row of KPI chips that both inform and filter the list below:
- **Overdue** — started covenants whose due date has passed without assessment *(red)*
- **Due this week** — next due within 7 days *(amber)*
- **Upcoming (30 days)** — next due in 8–30 days *(blue)*
- **Starting soon** — monitoring start date within the next 14 days *(grey)*

Each chip shows a count and, when clicked, filters the agenda/calendar to that bucket.

### 2. Agenda view (default)
A time-ordered list grouped by date (sections: **Overdue**, **Today**, **Tomorrow**, then each
upcoming date / week). Each event row shows:
`CP id · CP name | Covenant name | Frequency badge | timing chip | last result` and quick actions
**Run now** and **Open CP**. Overdue items pinned at the top in red.

### 3. Calendar view (toggle)
A month grid; each day cell shows event count dots colored by the worst status that day. Clicking a
day reveals that day's agenda. Month navigation; "Today" shortcut.

### Filters & controls
- Filter by **CP**, **status bucket**, and **date range** (default: all overdue + next 30 days).
- Search by CP id/name or covenant name.
- Sort agenda by due date (default) or by CP.
- Sensible defaults so the first paint shows "what's overdue + what's due in the next 30 days."

## Acceptance criteria

### AC1 — Aggregated schedule across CPs
```
Given I monitor multiple CPs with covenants on different frequencies and start dates
When I open the Monitoring Schedule
Then I see one combined, time-ordered list of monitoring events across all those CPs
And each event shows CP, covenant, frequency, due/start date, timing chip, and last result.
```

### AC2 — Summary buckets with counts
```
Given the schedule has loaded
Then I see chips for Overdue, Due this week, Upcoming (30 days), and Starting soon
And each shows an accurate count
And clicking a chip filters the list to that bucket (and clicking again clears it).
```

### AC3 — Overdue surfaced first
```
Given some covenants are overdue
Then they appear in a pinned "Overdue" section at the top, styled red, with "Overdue by N days"
And they are excluded from the "upcoming" sections.
```

### AC4 — Upcoming & starting-soon
```
Given covenants with future next-due dates and future start dates
Then upcoming assessments appear under their due-date groups with "Due in N days"
And covenants whose monitoring has not yet started appear as "Starts in N days" (start-date events)
And a not-yet-started covenant is never shown as overdue.
```

### AC5 — Calendar view
```
Given I switch to the Calendar view
Then I see a month grid with per-day event indicators colored by worst status
And clicking a day shows that day's events
And I can navigate months and jump to Today.
```

### AC6 — Filters & search
```
Given the schedule is open
When I filter by a CP, a status bucket, or a date range, or search a covenant name
Then the agenda and calendar update accordingly
And an empty result shows a friendly "No monitoring events match" state.
```

### AC7 — Drill-down & act
```
Given a monitoring event
When I click "Open CP" I land on that CP's detail (US-MON-08)
And when I click "Run now" the assessment is triggered and the event reflects the new last-result/next-due
And clicking the event opens the covenant's latest assessment if one exists.
```

### AC8 — Scope & performance
```
Given a portfolio of dozens of CPs and covenants
When the schedule loads
Then it defaults to "all overdue + next 30 days" and remains responsive (server-side date filtering;
     pagination or virtualization for long lists)
And the scope is the CPs the user monitors — the RM/WRM's assigned portfolio (see AC9), not all CPs.
```

### AC9 — Portfolio scoping (my-portfolio visibility) — *implemented*
```
Given I am signed in as a Relationship Manager (RM) or Wholesale RM (WRM)
When I open the Monitoring Schedule (or the By-CP summary, or drill into a CP)
Then I see only the CPs on which I am the RM or the WRM — never another user's CPs
And if I am mapped to no CPs (or my identity is unknown) the view is empty (fail-closed, no see-all).
```

## Data / API notes
- New endpoint, e.g. `GET /api/v1/monitoring/schedule?from=&to=&status=&cpId=` returning monitoring
  events: `{ cpId, cpName, appliedCovenantId, covenantName, frequency, startDate, nextDueDate,
  lastAssessedAt, lastResult, timingStatus }`, computed from `applied_covenant` + the schedule
  (Quartz next fire time) + latest `assessment`.
- Reuse the derived-field definitions from **US-MON-08** (single source of truth for
  nextDue/overdue/timingStatus).
- No new tables expected; if any caching/materialized table is added, it uses the `cov_` prefix.

### Portfolio scoping — implementation (AC9)
- **Identity:** the caller is resolved from the `X-User` request header (`CurrentUserContext`). The
  `MonitoringController` reads it once per request and passes it to every scoped read
  (`summary`, `schedule`, `covenantsForCp`).
- **Ownership store:** the CP-attributes cache `cov_credit_proposal_cache` carries `rm_user_id` and
  `wrm_user_id` (migration `V11`), populated best-effort from the external CP service
  (`CreditProposalClient.lookup`) whenever a CP's attributes are (re)fetched
  (`CachedCovenantAttributesService`). Enrichment is null-safe: a lookup failure or a missing rm/wrm
  never nulls a previously stored owner.
- **Filter:** `MonitoringService` computes the caller's owned CP ids via
  `CreditProposalCacheRepository.findCpIdsOwnedBy(user)` (`where rm_user_id = :u OR wrm_user_id = :u`)
  and restricts every read to that set. A blank/unknown user yields an **empty** set → empty results
  (fail-closed); there is **no** admin "see-all" bypass.
- **Trust boundary:** `X-User` is trustworthy only because a production gateway/SSO authenticates the
  user and injects/overrides it, stripping any client-supplied value. The frontend sends `X-User`
  **only in a DEV build** (a dev-only RM/WRM impersonation switcher); a production build never sends
  it. Without such a gateway this header is not, by itself, an access-control mechanism.

## Dependencies
- US-FREQ-07 (frequencies + start dates) and US-MON-08 (derived timing fields).
- Underlying scheduling must work (the separate dev `SCHEDULE_FAILED`-on-apply issue should be fixed
  so newly applied covenants get schedules and therefore next-due dates).

## Out of scope (flag for follow-up)
- Email/calendar (ICS) reminders and notifications.

## Implemented since draft
- **"My portfolio" scoping** (who owns which CPs) — the view is now scoped to the signed-in RM/WRM's
  portfolio via the `X-User` identity and `rm_user_id`/`wrm_user_id` on `cov_credit_proposal_cache`
  (see AC9 and the "Portfolio scoping — implementation" notes above). Fail-closed; no see-all bypass.
