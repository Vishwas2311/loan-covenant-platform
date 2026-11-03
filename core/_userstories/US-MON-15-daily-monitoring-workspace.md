# US-MON-15 — Daily monitoring workspace: By-CP & By-Covenant views

**Epic:** F — RM assessment, disposition & evidence
**Status:** Draft · **Priority:** High

## Story
**As a** Relationship Manager / Wholesale Relationship Manager acting on a daily breach email
**I want** one monitoring landing screen where I can see my action points grouped **by CP** or listed
**by covenant**, and drill into any CP to assess it
**so that** I can triage what needs action the day I receive a breach notification — without hunting
across separate read-only screens.

## Problem with today's screens
After the US-MON-14 breach-notification run emails each RM (cc WRM) a per-CP monitoring summary, the
owners log in to act on it — but the app has **nowhere to act**. The monitoring surfaces are all
read-only visibility: the US-MON-08 per-CP timeline, the US-MON-09 portfolio schedule, and the
US-MON-14 dashboard summary. There is no single landing screen that both **summarises what needs
action** and **leads into a place to record an outcome**. The RM has to jump between screens and then
fall back to the single-covenant assessment page to do anything.

## Solution (design)
Introduce one **Monitoring workspace** that unifies the two existing lenses behind a
**By CP / By Covenant** toggle (a Mantine `SegmentedControl`), and makes every CP a drill-in point
into the new CP assessment screen (US-MON-16):

### By CP (summary)
The US-MON-14 dashboard summary table, unchanged in meaning — one row per CP with the
`CpMonitoringSummary` counts (CP, Applied, Compliant, Breached, Error, Pending) — ordered
most-actionable first (breaches/errors on top) so the day's work is obvious. Each row drills into the
CP assessment screen.

### By Covenant (agenda / calendar)
The US-MON-09 portfolio schedule, reused as-is: overdue pinned at the top; Overdue / Due-soon /
Upcoming / Starting-soon buckets; CP + status-bucket + date-range filters and covenant/CP search.
Each agenda event keeps its **Open CP** action, which now leads into the CP assessment screen.

### Shared toolbar
The US-MON-14 **Assessment date** picker + **Run & notify** action stay on this workspace, so an RM
can re-run the sweep for a chosen date from the same screen they triage on.

## Acceptance criteria

### AC1 — One workspace, two toggleable views
```
Given I open the Monitoring workspace
Then I can switch between a "By CP" view and a "By Covenant" view via a single toggle
And my chosen view persists for the session.
```

### AC2 — By CP reuses the dashboard summary
```
Given the By CP view is selected
Then I see one row per CP with the US-MON-14 counts (CP, Applied, Compliant, Breached, Error, Pending)
And the most actionable CPs (breaches/errors) are ordered first
And the counts equal the CP Monitoring screen's counts (US-MON-14 AC3 semantics unchanged).
```

### AC3 — By Covenant reuses the portfolio schedule
```
Given the By Covenant view is selected
Then I see the US-MON-09 agenda/calendar unchanged: overdue pinned, Overdue/Due-soon/Upcoming/
     Starting-soon buckets, with search and date-range filtering
And no US-MON-09 behaviour regresses.
```

### AC4 — Drill into a CP to assess it
```
Given either view is showing
When I select a CP (a By-CP row, or "Open CP" on a By-Covenant agenda event)
Then the CP assessment screen (US-MON-16) opens for that CP.
```

### AC5 — Run & notify stays available
```
Given I am on the Monitoring workspace
Then the US-MON-14 assessment-date picker and "Run & notify" action are available here
And running it behaves exactly as on the US-MON-14 dashboard (same result notice + refresh).
```

### AC6 — No regression to existing monitoring
```
Given the workspace wraps the existing US-MON-09 and US-MON-14 surfaces
Then their existing query keys and cache invalidation continue to work
And loading, filtering, and refreshing behave as before the workspace was introduced.
```

## Data / API notes
- **No new backend endpoint.** The workspace composes existing calls: the US-MON-14 summary
  (`GET /api/v1/monitoring/summary`), the US-MON-09 schedule
  (`GET /api/v1/monitoring/schedule?from=&to=&status=&cpId=`), and the US-MON-14 run trigger
  (`POST /api/v1/monitoring/notification-runs?date=`).
- Frontend only: a new `MonitoringWorkspace` wrapper with a `SegmentedControl`; the By-Covenant view
  renders the existing schedule components, the By-CP view the existing summary table. Existing query
  keys (`['monitoring','summary']`, `['monitoring','schedule',…]`) are reused unchanged.
- Selecting a CP navigates to a new `cp-assessment` view (see US-MON-16) via the app's existing
  view-state routing (`App.tsx`).
- **Portfolio scoping is inherited, not re-implemented.** Because both composed reads
  (`/monitoring/summary` and `/monitoring/schedule`) are now scoped to the signed-in RM/WRM by
  `X-User` (see US-MON-09 AC9), the By-CP and By-Covenant views — and the CP drill-in
  (`/monitoring/cps/{cpId}/covenants`) — automatically show only the caller's own CPs. In a DEV build
  the header's RM/WRM impersonation switcher drives this; switching identity invalidates all queries so
  both views refetch for the new portfolio.

## Dependencies
- US-MON-09 (portfolio schedule) and US-MON-14 (dashboard summary + run) — the two views this
  workspace unifies.
- US-MON-16 — the CP assessment screen this workspace drills into.

## Out of scope (flag for follow-up)
- Cross-session persistence of the By-CP / By-Covenant choice (session-scoped only).

## Implemented since draft
- A **"my portfolio" ownership model** (which RM/WRM owns which CPs) — the workspace no longer spans
  all CPs; both views are scoped to the signed-in RM/WRM's portfolio, inherited from US-MON-09's
  `X-User` + `rm_user_id`/`wrm_user_id` filter (see the "Portfolio scoping" note above). Fail-closed.
