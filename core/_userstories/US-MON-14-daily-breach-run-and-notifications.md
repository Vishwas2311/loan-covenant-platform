# US-MON-14 — Daily breach-assessment run & RM/WRM monitoring notifications

**Epic:** Monitoring scheduling & visibility
**Status:** Draft · **Priority:** High

## Story
**As a** Relationship Manager / Covenant Admin responsible for a portfolio of CPs
**I want** a daily (and on-demand, date-selectable) run that assesses every covenant due that day and
emails each RM — copying the WRM — a per-CP monitoring summary
**so that** the right owners are alerted to breaches automatically, without running each covenant by
hand and without being spammed with one email per CP.

## Problem with today's behaviour
Covenants are assessed **one at a time**: an analyst clicks *Run* on a single applied covenant
(`AssessmentService.runAssessment`), or the per-covenant Quartz `AssessmentJob` fires on that one
covenant's own schedule. There is **no portfolio-wide daily sweep** and **no outbound notification**
when covenants come due or breach. Owners only find out by opening each CP. The bank wants a single
scheduled run that assesses everything due, then tells each covenant's owners how their CPs look —
in one consolidated message per owner.

## Solution (design)
A **breach-notification run** — schedulable daily and triggerable on demand from the Monitoring
dashboard — that, for an assessment date (today by default, or a UI-selected date):

1. **Finds every ACTIVE covenant due on that date** (including overdue) using the same
   `MonitoringTimingCalculator` the CP Monitoring screen and portfolio schedule (US-MON-08/09) use —
   *not* every active covenant, only those actually due.
2. **Assesses each** with the shared `AssessmentService.runAssessment(id, SCHEDULED, actor)`
   primitive — the exact call the per-covenant job makes — so assessment behaviour is identical and
   `cov_assessment` stays append-only. Each assessment runs in its own transaction so one failure
   never aborts the batch.
3. **Builds each touched CP's monitoring summary** — the same Applied / Compliant / Breached / Error /
   Pending counts shown on the CP Monitoring screen — via `MonitoringService.summaryFor(cpId)`, the
   single implementation the screen and the run share.
4. **Fetches each CP's RM and WRM** (name + email) from the credit-proposal source, then **merges CPs
   by their (RM, WRM) pair**: one email per distinct pair — **RM → `emailTo`, WRM → `emailCC`** —
   listing every one of that pair's CPs. CPs that share an RM/WRM are consolidated into a single
   message.
5. **Sends each pair one notification** via an external notification service through a **resilient**
   client (timeout + retry + circuit breaker); on failure the run still completes and the failure is
   recorded — onboarding and monitoring are never affected.
6. **Audits every call** to an append-only `cov_notification_audit` table (request, response, HTTP
   status, timing, outcome, acting user, shared `runId`), including one row for any touched CP group
   that had no RM to notify.

### Frontend
The Monitoring dashboard gains a small toolbar: an **Assessment date** picker (defaults to today) and
a **Run & notify** button. Clicking it triggers the run for the picked date, shows a result notice
(*"Assessed N covenants across M CPs — sent K, F failed."*) and refreshes the summary table. On the
resilient fallback the run still returns with a non-zero failed count, and the UI reports it — the
same graceful degradation the ML onboarding flow uses.

### Idempotency (same-day re-run safety)
The run does **not** need a bespoke "already assessed today" guard. `MonitoringTimingCalculator`
advances a covenant's next due date to the first cron occurrence **strictly after** its last
assessment, so a covenant assessed earlier on the run date (by an earlier run, or by the per-covenant
job) is no longer *due* and is skipped. A same-day re-run therefore neither double-assesses nor
double-emails.

## Acceptance criteria

### AC1 — Scope: due covenants on the run date
```
Given ACTIVE covenants on various frequencies and start dates
When the run executes for an assessment date (today by default, or a date chosen in the UI)
Then only covenants due on that date — including overdue ones — are assessed
And covenants not yet due, ended, or already assessed on that date are skipped
And suspended/expired covenants are never assessed.
```

### AC2 — Execution parity with an individual run
```
Given a due covenant
When the run assesses it
Then it uses the same assessment primitive as a manual/scheduled single run (trigger = SCHEDULED)
And a new append-only cov_assessment row is produced
And one covenant's assessment failure is logged and skipped without aborting the batch.
```

### AC3 — Per-CP summary equals the monitoring screen
```
Given a CP touched by the run (it had at least one covenant assessed)
Then its summary counts (Applied, Compliant, Breached, Error, Pending) equal the CP Monitoring
     screen's counts for that CP
And NA results count only toward Applied (the total), exactly as on the screen.
```

### AC4 — Recipients merged by (RM, WRM) pair
```
Given the touched CPs and their RM/WRM (name + email) fetched from the CP source
When notifications are built
Then CPs are grouped by their (RM email, WRM email) pair
And each distinct pair receives exactly one email — RM as emailTo, WRM as emailCC —
And that email lists every CP belonging to the pair with its counts
And a CP whose RM has no usable email is assessed but not emailed (recorded as no-recipient).
```

### AC5 — Notification payload contract
```
Given a (RM, WRM) pair's email
Then the request body is
  { moduleCode: "COV", templateCode: "Cov_Template",
    emailTo: <RM email>, emailCC: <WRM email>,
    variables: { cpMonitoring: [ { CP_ID, Applied, Compliant, Breached, Error, Pending }, ... ] },
    priority: "HIGH" }
And the cpMonitoring key names/order mirror the CP Monitoring screen columns.
```

### AC6 — Resiliency (never breaks the caller)
```
Given the external notification service is slow, erroring, or down
When the run sends a notification
Then the call is protected by timeout + retry + circuit breaker
And on failure the client returns a failed result rather than throwing
And the run still completes, reporting the failed count — no crash, monitoring unaffected.
```

### AC7 — Append-only audit of every call
```
Given any notification attempt (success, failure, or no-recipient group)
Then one cov_notification_audit row is appended with the request body, response body, HTTP status,
     outcome (SUCCESS/FAILED), duration, acting user, and the run's shared runId
And audit rows are never updated or deleted.
```

### AC8 — Scheduled daily + on-demand triggers
```
Given the application is running
Then a daily Quartz trigger (default 06:00, configurable) runs the sweep for "today" as the system user
And the trigger is registered idempotently at startup (an app restart does not duplicate it)
And a user can trigger the run on demand from the Monitoring dashboard for a chosen date
And the on-demand run is attributed to the acting user (falling back to the system user).
```

## Data / API notes
- New endpoint `POST /api/v1/monitoring/notification-runs?date=YYYY-MM-DD` (date optional ⇒ today)
  returning `{ runId, covenantsAssessed, cpsTouched, notificationsSent, notificationsFailed }`.
- New table **`cov_notification_audit`** (Flyway `V33`), append-only, `cov_` prefixed, MSSQL/H2
  compatible: `run_id`, `email_to`, `email_cc`, `cp_ids`, `request_body`, `response_body`,
  `http_status`, `outcome` (`SUCCESS`/`FAILED`), `error_message`, `duration_ms`, `changed_by`,
  `changed_at`.
- CP details (`CreditProposalDetails`) gain `rm` / `wrm` (`CpUser{ userId, name, email }`), fetched
  live from the existing credit-proposal lookup; unknown/absent ⇒ null (old responses keep working).
- External notification service base URL + `resilience4j` `notifications` instance are externally
  configured; dev points at WireMock, test at a dead port.
- Reuses the derived timing definitions from **US-MON-08/09** (single source of truth for
  due/overdue), the `AssessmentService` primitive, and the `ScheduleService` Quartz idiom.

## Dependencies
- US-MON-08 (derived timing fields) and US-FREQ-07 (frequency + start date) — drive "due on date".
- US-ASM-05 / assessment engine — the per-covenant run primitive reused here.
- Credit-proposal profile must expose the CP's RM and WRM (name + email).

## Out of scope (flag for follow-up)
- A **breached-only** notification filter (currently every touched CP with a recipient is included;
  this is a one-line predicate if wanted later).
- Recipient models beyond a single RM + single WRM per CP.
- In-app notification templating/preview; the template (`Cov_Template`) is owned by the external
  service.
