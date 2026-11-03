# US-CM-22 — CP-scoped custom (manual) covenant

**Epic:** Covenant eligibility, classification & assessment type
**Status:** Draft (documents shipped behaviour) · **Priority:** Medium

> **Superseded in part by [US-CM-24](US-CM-24-custom-covenant-assessment-type.md).** The fixed
> `MANUAL` assessment type / `Non-financial` category / empty `referencedMeasures` described below
> (AC3) no longer hold: a custom covenant is now **Auto or Manual**, chosen in the modal (default
> Auto), with the category derived from that choice. Everything else in this story still stands.

## Story
**As an** RM / Credit Analyst onboarding a credit proposal (CP)
**I want** to add a one-off **custom covenant** to *this CP* during onboarding, without it entering the
shared covenant library
**so that** I can capture a bespoke, manually-verified obligation that no library covenant covers,
apply it to the CP alongside recommended and library covenants, and monitor it like any other.

## Description
On the CP Onboarding wizard's recommendations step, alongside **Add from library**, an **Add custom
covenant** button opens a modal that creates a **CP-scoped, MANUAL** covenant and adds it to this CP's
covenant list (tagged **Custom**). It is *not* a library covenant: it exists only for the CP that
created it and never appears in another CP's recommendation pool.

Because a custom covenant is always **Manual** (US-CM-03), its **Definition** is a free-text
instruction an analyst verifies — never a measure formula. The remaining covenant-master fields the
authoring screen would normally require (eligibility dimensions, Islamic banking, category) are **not**
asked for; they are derived automatically:

- **Eligibility** is copied from the CP's own attributes (the same `CpEligibility` projection the
  recommendation engine uses), so the covenant is trivially "eligible" for its owning CP.
- **Assessment type** = `MANUAL`; **Category** = `Non-financial`; `referencedMeasures` = empty.
- The covenant is flagged `isCustom = true` with `ownerCpId` = this CP.

The modal also captures the **per-CP apply-time** values needed to place the covenant on the grid —
**Monitoring frequency** (+ custom cron), **Monitoring start date**, **Condition type**
(Precedent/Subsequent) and **Classification** (External/Internal). Of these, only *name, frequency,
cron, definition* are sent to the create-custom endpoint (they define the master); **start date,
condition type and classification are apply-time attributes** of the applied covenant (US-FREQ-07) and
ride along into the Apply step, not onto the master.

### UX (Onboarding wizard → "Add custom covenant" modal)
Fields (all required unless noted):
- **Covenant name** — text, ≤ 255 chars.
- **Monitoring frequency** — the shared `FrequencyPicker`; when `CUSTOM`, a Quartz **cron** is required.
- **Monitoring start date** — date, must be today or later (apply-time; not on the master).
- **Condition type** — Precedent | Subsequent (apply-time; not on the master).
- **Classification** — External | Internal (apply-time; not on the master).
- **Definition** — free-text instruction (multi-line, ≤ 2000 chars); labelled as a manual covenant,
  not a formula.

Submit is disabled until name, definition, start date, condition type and classification are set (and
a cron is present when frequency = Custom). On success a new grid row appears tagged **Custom**,
pre-filled with the chosen frequency/cron/start date/condition type/classification, selected for apply.

## Acceptance criteria

### AC1 — Entry point
```
Given the CP Onboarding recommendations step
Then I see an "Add custom covenant" action beside "Add from library"
And activating it opens the "Add custom covenant" modal.
```

### AC2 — Required fields & client validation
```
Given the Add custom covenant modal
Then Covenant name, Definition, Monitoring start date, Condition type and Classification are required
And when frequency = Custom a cron expression is required
And the submit button stays disabled until all required fields are provided
And the start date cannot be before today.
```

### AC3 — Creates a CP-scoped MANUAL covenant
```
Given a valid custom covenant form for CP X
When I submit
Then a covenant is created with assessmentType = MANUAL, category = Non-financial,
     isCustom = true and ownerCpId = X
And its condition/definition text is stored verbatim with no formula validation and empty referencedMeasures
And its eligibility (Islamic banking + all eligibility dimensions) is copied from CP X's attributes
And it is added to CP X's recommendation list with status MANUAL_ADD and reason "Custom covenant"
And the API responds 201 with the created covenant.
```

### AC4 — Scoped to its CP only (never enters the shared pool)
```
Given a custom covenant owned by CP X
When recommendations are computed for a different CP Y
Then the custom covenant is never offered to CP Y
And it is excluded from the general eligibility-matching pool and from ML "add new covenant" candidates
But it always appears on CP X's own list, tagged Custom.
```

### AC5 — Duplicate name guard (per CP)
```
Given CP X already has a custom covenant named "Board resolution within 30 days"
When I try to create another custom covenant with the same name (case-insensitive) for CP X
Then the request is rejected as a duplicate (409)
And no second covenant is created.
```

### AC6 — Frequency & cron
```
Given frequency = Custom
When the cron expression is missing or not a valid Quartz expression
Then the server rejects the request (400 INVALID_CRON)
And when frequency is a standard cadence, no cron is stored (defaultCron = null).
```

### AC7 — Apply-time attributes carried into the grid
```
Given a custom covenant was created from the modal
Then the new grid row is pre-filled with the chosen monitoring frequency (+ cron),
     start date, condition type and classification
And those per-CP values are persisted on the applied covenant at the Apply step (US-FREQ-07),
     not on the covenant master.
```

## Interaction with other features
- **US-CM-24 (Assessment type for a custom covenant):** supersedes the always-`MANUAL` rule — the modal
  now offers **Auto | Manual** (default Auto) and derives the category from it. An Auto custom covenant
  is authored in the Formula Builder, validated server-side and auto-evaluated.
- **US-CM-03 (Assessment type):** a `MANUAL` custom covenant has no formula builder, no validation and
  no thresholds.
- **US-REC-04 (Recommendation matching):** owner-CP custom covenants are appended to the CP's list
  outside the eligibility match (they are always shown for their CP); they never match other CPs.
- **US-FREQ-07 (Frequency + start date; condition type & classification):** frequency/cron seed the
  master default; start date, condition type and classification are apply-time (per-CP) values.
- **US-ML-12 / US-ML-20 (ML thresholds):** ML candidate/threshold logic explicitly skips `isCustom`
  covenants (no parseable formula to tune).

## Data / API notes
- **Endpoint:** `POST /api/v1/recommendations/{cpId}/custom-covenants` → `201 Created`, returns the
  created `RecommendedCovenant`. Handler: `RecommendationController.createCustom` →
  `RecommendationService.createCustomForCp`.
- **Request body** `CreateCustomCovenantRequest`: `name` (NotBlank, ≤255), `frequency` (NotNull),
  `cronExpression` (≤64, required only when `frequency = CUSTOM`), `definition` (NotBlank, ≤2000).
- **Persistence:** a `cov_covenant_master` row (`is_custom = 1`, `owner_cp_id = {cpId}`,
  `assessment_type = MANUAL`, `category = non_financial`, eligibility copied from the CP) plus a
  `cov_covenant_recommendation` row (`status = MANUAL_ADD`, `match_reason = "Custom covenant"`).
- **Uniqueness:** enforced in-service via `existsByOwnerCpIdAndNameIgnoreCase` (per-CP, case-insensitive).
- **Frontend:** `CustomCovenantModal` inside `CpOnboardingWizard.tsx`; `onboardingApi.createCustomCovenant`.

## Dependencies
- US-CM-03 (assessment type — Manual half), US-ASM-05 (manual assessment execution),
  US-REC-04 (recommendation list), US-FREQ-07 (per-CP frequency/start date, condition type,
  classification).
