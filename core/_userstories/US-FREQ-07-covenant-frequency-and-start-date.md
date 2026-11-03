# US-FREQ-07 — Per-covenant default frequency + per-CP override (frequency & start date)

**Epic:** Monitoring scheduling & visibility
**Status:** Draft · **Priority:** High

## Story
**As a** Covenant Administrator (setting defaults) and a Relationship Manager (onboarding a CP)
**I want** each covenant to carry its own default monitoring frequency, and to be able to override
the frequency **and** the monitoring start date per CP in the Configure step
**so that** every covenant is monitored on the right cadence for the deal, starting on the right
date — instead of a one-size-fits-all default.

## Description
Today frequency is chosen per applied covenant in the wizard (hard-defaulted to *Monthly*), and the
monitoring start date is silently set to "today" at apply time. This story makes both first-class:

1. **Covenant master default frequency** — a covenant defines its *recommended* monitoring frequency
   (`DAILY | WEEKLY | MONTHLY | QUARTERLY | YEARLY | CUSTOM`, with a cron for `CUSTOM`). This is the
   sensible default for that covenant type (e.g. DSCR → Quarterly, insurance check → Yearly).
2. **Per-CP override in Configure** — in the wizard's **Configure** step, each selected covenant's
   frequency picker is **pre-filled from the master default** and can be overridden for this CP.
   The same step also exposes a **Monitoring start date** per covenant (default = today; may be a
   future date when monitoring should begin later).
3. **Effective values shown** — the applied covenant stores the chosen frequency + start date, and
   they are displayed wherever the covenant appears (CP detail, monitoring — see US-MON-08/09).
4. **Per-CP condition type & classification** — in the same **Configure** step, each selected
   covenant (library, manually added, **or custom**) can be tagged with a **Condition type**
   (`PRECEDENT` = condition precedent / `SUBSEQUENT` = condition subsequent) and a **Classification**
   (`EXTERNAL` / `INTERNAL`). These describe *how this covenant applies to this specific deal*, so
   they live on the **applied covenant** (per CP), **not** on the covenant master. Both are optional
   and default to unset (null); the same covenant may carry different values on different CPs.

> **Moved from the master:** condition type & classification were previously fields on the covenant
> master (and its create/edit/detail screens and the custom-covenant form). They are no longer stored
> on the master; they are captured per CP at apply time (Configure step) and returned on the applied
> covenant. Existing applied covenants created before this change carry null for both.

## Acceptance criteria

### AC1 — Covenant master has a default frequency
```
Given the covenant create/edit screen
Then it has a "Default monitoring frequency" field (Daily/Weekly/Monthly/Quarterly/Yearly/Custom)
And Custom requires a valid cron
And it defaults to Monthly for a new covenant
And it is shown on the covenant detail screen.
```

### AC2 — Configure pre-fills from the master default
```
Given I reach the Configure step for selected covenants
Then each covenant's frequency is pre-filled with that covenant's default frequency (not a global default)
And I can change it for this CP without affecting the covenant master.
```

### AC3 — Per-CP monitoring start date
```
Given a covenant in the Configure step
Then it shows a "Monitoring start date" picker defaulting to today
And I can set it to a future date
And the chosen start date is stored on the applied covenant
And a past start date is rejected only if product rules require it (default: today or future).
```

### AC4 — Effective frequency/start date persisted & displayed
```
Given I apply covenants with per-CP frequency and start date
Then each applied covenant persists its own frequency, cron, and start date
And the CP detail screen shows the effective frequency and start date per covenant.
```

### AC5 — Custom cron validation
```
Given a covenant (default or per-CP) set to Custom frequency
When the cron is missing or invalid
Then save/apply is blocked with a clear message (INVALID_CRON), matching today's behaviour.
```

### AC6 — Backward compatibility
```
Given covenants/applied covenants created before this feature
Then covenants with no default frequency are treated as Monthly
And applied covenants keep their existing frequency and start date
And applied covenants created before condition type/classification existed carry null for both
And nothing breaks on load, edit, or assessment.
```

### AC7 — Per-CP condition type & classification
```
Given a covenant in the Configure step (recommended, added from library, or custom)
Then it shows an optional "Condition type" (Condition precedent / Condition subsequent)
And an optional "Classification" (External / Internal)
And both default to unset and may be left blank
And the chosen values are stored on the applied covenant for this CP
And they are NOT written to the covenant master
And the CP detail / monitoring screens show the applied covenant's own values
And applying the same covenant to another CP may use different values.
```

## UX notes
- In Configure, show frequency + start date together on each covenant card (alongside the threshold
  editor) so all per-CP tuning for a covenant lives in one place.
- When a covenant's per-CP frequency/start date differs from the covenant default, show a subtle
  "overridden" hint so the user knows they've diverged from the recommendation.

## Data / API notes
- `covenant_master`: add `default_frequency` (+ `default_cron`). `ApplyCovenantRequest`: add
  `startDate` (and keep `frequency`/`cronExpression`). `AppliedCovenant.startDate` already exists —
  set it from the request instead of `now()`.
- **Condition type & classification (per CP):** `ApplyCovenantRequest` also gains optional
  `conditionType` (`PRECEDENT`/`SUBSEQUENT`) and `classification` (`EXTERNAL`/`INTERNAL`); they are
  persisted on `cov_applied_covenant` (`condition_type`, `classification` — nullable, with CHECK
  constraints) and returned on `AppliedCovenantResponse`. The matching columns were **dropped** from
  `cov_covenant_master`, and removed from `CreateCustomCovenantRequest` and the covenant master DTOs.
- Any **new** table follows the `cov_` prefix (none expected here; these are columns on existing
  tables).

## Dependencies / related
- Configure step already hosts the threshold editor (per-CP override). This adds frequency + start
  date to the same step. Feeds US-MON-08 and US-MON-09.
  [US-CM-25](US-CM-25-per-cp-covenant-definition-and-audit.md) later widens that override from
  *thresholds only* to the **whole condition** (and replaces the threshold editor with the Formula
  Builder); per-CP frequency, start date, condition type and classification are untouched by it.
- The dev-only `SCHEDULE_FAILED`-on-apply issue (separate) must be resolved for scheduling to work
  end-to-end.
