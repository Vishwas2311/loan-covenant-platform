# Covenant Monitoring System — User Stories

## Personas

| ID | Persona | Description |
|---|---|---|
| **P1** | Covenant Library Admin | Risk policy / financial controls team. Owns the covenant master rules. Authors and maintains the library. |
| **P2** | Credit Operations Officer | Onboards CPs into monitoring. Picks covenants per CP, sets frequencies, activates schedules. |
| **P3** | Credit Risk Assessor / Relationship Manager | Reviews assessment results, acts on breaches via the workflow service. |
| **P4** | Compliance / Auditor | Reads historical assessments to evidence covenant monitoring during audits. |
| **P5** | System (Scheduler) | Non-human actor — Quartz scheduler triggers assessments on cron ticks. |
| **EXT-CP** | External: Credit Proposal Module | Issues `CP_ID`s and serves CP attribute data. |
| **EXT-DATA** | External: Data API | Serves current metric values and CP-specific threshold values. |
| **EXT-WF** | External: Workflow Service | Runs the human-review workflow per assessment. |

## Priority Legend

- **P0** — Must have for MVP launch (blocking).
- **P1** — Should have in v1 (close behind MVP).
- **P2** — Nice to have / next iteration.

---

## Epic 1 — Covenant Master Library Management

### US-001 — Create a covenant in the master library
**Persona**: P1 Covenant Library Admin · **Priority**: P0

**Story**: As a Covenant Library Admin, I want to create a new covenant in the master library with its name, category, type, business segments, description, and parameterised rule, so that it can later be applied to credit proposals.

**Acceptance Criteria**
- **AC1**: Given I am on `/covenants/new`, when I submit a valid form, then a new covenant is created with status `active=true` and is returned with a generated `id`.
- **AC2**: Given I leave `name`, `category`, `type`, or `ruleExpression` empty, when I submit, then I see field-level validation errors and no row is created.
- **AC3**: Given `category` is one of {FINANCIAL, NON_FINANCIAL}, when I select a value, then it persists exactly as enum.
- **AC4**: Given I add `metricKeys` and `thresholdKeys` as tag chips, when I save, then both are persisted as JSON arrays.
- **AC5**: Given two covenants share the same `name` (case-insensitive), when I save the second, then I get a 409 Conflict — names are unique within the library.
- **AC6**: Given the request succeeds, when I navigate to `/covenants`, then the new covenant appears at the top of the list.

**Definition of Done**: Backend endpoint `POST /api/v1/covenants` + unique constraint + service-layer validation + UI form + TanStack mutation that invalidates the list query.

---

### US-002 — Edit an existing covenant in the master library
**Persona**: P1 · **Priority**: P0

**Story**: As a Covenant Library Admin, I want to edit an existing covenant's fields (name, description, rule, segments) so that I can correct mistakes or evolve policy without creating a new entry.

**Acceptance Criteria**
- **AC1**: Given a covenant exists with `id=X`, when I `GET /api/v1/covenants/X`, then I receive its full payload including audit columns.
- **AC2**: Given I edit the rule expression, when I save, then `updatedAt` and `modifiedBy` change; the `id` and `createdAt` do not.
- **AC3**: Given the covenant is already applied to one or more CPs, when I edit the rule expression, then I see a warning listing the impacted `appliedCovenant` counts before confirming save.
- **AC4**: Given I confirm the warning, when I save, then future assessments use the new rule; past `Assessment` rows are not retroactively changed (`evaluatedExpression` is frozen on each assessment).
- **AC5**: Every edit appends a row to `expression_change_log` capturing `covenantId`, `oldExpression`, `newExpression`, `changedBy`, `changedAt`.

**Definition of Done**: `PUT /api/v1/covenants/{id}` + change-log table + UI confirmation modal showing impact count.

---

### US-003 — Deactivate a covenant (soft delete)
**Persona**: P1 · **Priority**: P0

**Story**: As a Covenant Library Admin, I want to deactivate a covenant so that it stops appearing in recommendations for new CPs, without breaking historical references.

**Acceptance Criteria**
- **AC1**: Given a covenant is `active=true`, when I click "Deactivate", then I am prompted for confirmation listing currently-active applied instances.
- **AC2**: Given I confirm, when the call returns, then `active=false` is persisted and the row disappears from the default list view (which filters to active).
- **AC3**: Given an `AppliedCovenant` references the deactivated covenant, when I view that CP's covenants, then it is still shown but with an "Inactive in library" badge.
- **AC4**: Given a covenant is `active=false`, when the recommendation engine runs for a new CP, then this covenant is **never** suggested.
- **AC5**: A deactivated covenant can be re-activated by an admin via the same toggle.

**Definition of Done**: Endpoint `PATCH /api/v1/covenants/{id}` with `{active:false}` + filter on list + recommendation query excludes inactive.

---

### US-004 — Search and filter the covenant master library
**Persona**: P1 · **Priority**: P0

**Story**: As a Covenant Library Admin, I want to search the library by name and filter by category, type, business segment, and active status, so that I can find a covenant in a library that may eventually contain hundreds of entries.

**Acceptance Criteria**
- **AC1**: Given I type in the search box, when I pause for 300ms (debounce), then the list filters to rows where `name` or `description` contains the query (case-insensitive).
- **AC2**: Given I select multiple filters (e.g. `category=FINANCIAL` AND `businessSegment=RETAIL`), when filters are applied, then only rows matching all selected filters are returned.
- **AC3**: Given I clear filters, when I click "Reset", then the full active list is shown again.
- **AC4**: Given there are more than 50 rows, when the list loads, then it is paginated server-side with default page size 25.
- **AC5**: Sort by name, type, category, or `updatedAt` (column header click toggles asc/desc).

**Definition of Done**: `GET /api/v1/covenants?search=&category=&type=&segment=&active=&page=&size=&sort=` + Mantine table with TanStack Table sorting/pagination.

---

### US-005 — View covenant detail with usage statistics
**Persona**: P1 · **Priority**: P1

**Story**: As a Covenant Library Admin, I want to view a covenant's detail page showing the rule, declared variables, and how many CPs currently use it, so that I can understand impact before editing.

**Acceptance Criteria**
- **AC1**: Given I click a covenant row, when the detail page loads, then I see all master fields plus a "Currently applied on N CPs" metric.
- **AC2**: Clicking the count drills into a list of CP_IDs where this covenant is `ACTIVE`.
- **AC3**: The page shows the change log (US-002 AC5) as a chronological table.

**Definition of Done**: `GET /api/v1/covenants/{id}/usage` + detail page with drill-down.

---

## Epic 2 — Rule Authoring & Validation

### US-006 — Define rule using SpEL with declared variables
**Persona**: P1 · **Priority**: P0

**Story**: As a Covenant Library Admin, I want to write rule expressions in SpEL referencing `#metricKey` and `#threshold_metricKey` variables, so that the same rule template works across CPs with different thresholds.

**Acceptance Criteria**
- **AC1**: Given I write `#DSCR > #threshold_DSCR` and declare both keys, when I save, then the covenant persists.
- **AC2**: Given I reference `#foo` but `foo` is not in `metricKeys ∪ thresholdKeys`, when I save, then I get a 400 with message "Undeclared variable: foo".
- **AC3**: Given my expression has a syntax error (e.g. unmatched `(`), when I save, then I get a 400 with the SpEL parser error.
- **AC4**: Given my expression evaluates to a non-Boolean type (e.g. `#DSCR + 1`), when I save, then I get a 400 — covenants must yield Boolean.
- **AC5**: Compound expressions with `and` / `or` / `not` are supported.

**Definition of Done**: Save-time validation in `CovenantMasterService` covers steps 1, 2, 3 of the deep-dive's validation list.

---

### US-007 — Validate rule expression interactively before save
**Persona**: P1 · **Priority**: P0

**Story**: As a Covenant Library Admin, I want a "Validate" button next to the rule expression input so that I can confirm correctness without submitting the whole form.

**Acceptance Criteria**
- **AC1**: Given I type an expression and click "Validate", when the call resolves, then I see either a green "Valid" badge or a red error list inline.
- **AC2**: Validate runs the same checks as save (parse, reference, dry-run with dummy values, operator denylist).
- **AC3**: Validate never persists anything — it is idempotent and read-only on the server.
- **AC4**: The validate response includes the **resolved sample expression** (e.g. `1.0 > 1.0`) so the admin can see how their rule will read after substitution.

**Definition of Done**: `POST /api/v1/covenants/validate-expression` returning `{valid, errors[], sampleResolution}` + UI integration.

---

### US-008 — Author qualitative (non-financial) covenants
**Persona**: P1 · **Priority**: P0

**Story**: As a Covenant Library Admin, I want to author qualitative covenants like "Auditor must be E&Y", so that non-financial checks are monitored alongside financial ones.

**Acceptance Criteria**
- **AC1**: Strings in expressions use single quotes: `#auditor == 'E&Y'`.
- **AC2**: Allowlists use SpEL inline collections: `#auditor in {'E&Y','KPMG','Deloitte','PwC'}`.
- **AC3**: When the metric value comes back as `null`, the assessment is `ERROR` (not `BREACHED`).
- **AC4**: The UI's Covenant Form does not constrain non-financial expressions differently — same input, same validation.

**Definition of Done**: Examples covered in the SpEL evaluator unit tests; UI demonstrates a qualitative covenant in the seed data.

---

### US-009 — Guard SpEL execution against injection
**Persona**: P1 · **Priority**: P0

**Story**: As a Covenant Library Admin, I want the system to refuse rule expressions containing dangerous SpEL constructs, so that no admin can accidentally (or maliciously) call `Runtime.getRuntime().exec(...)` from a rule.

**Acceptance Criteria**
- **AC1**: Given an expression contains `T(`, `new `, `.class`, `@`, `?.`, or `?[`, when I save, then I get a 400 "Disallowed token".
- **AC2**: Evaluation uses `SimpleEvaluationContext.forReadOnlyDataBinding().withInstanceMethods().build()` — confirmed by unit test that `T(java.lang.Runtime).getRuntime()` fails to evaluate.
- **AC3**: Even with the denylist bypassed, the evaluation context cannot reflect into types — second line of defence proven by test.

**Definition of Done**: Denylist + `SimpleEvaluationContext` wired in `RuleEvaluator` + dedicated security test class.

---

## Epic 3 — CP Onboarding & Recommendation

### US-010 — Initiate covenant setup for a new CP_ID
**Persona**: P2 Credit Operations Officer · **Priority**: P0

**Story**: As a Credit Operations Officer, I want to enter a `CP_ID` and start the covenant setup wizard, so that I can apply covenants to a newly-approved credit proposal.

**Acceptance Criteria**
- **AC1**: Given I navigate to `/cp-onboarding` and enter `CP-1001`, when I click "Lookup", then the system calls the external CP module and displays the CP's name, business segment, sector, and approval status.
- **AC2**: Given the CP_ID is unknown to the external CP module, when the lookup fails with 404, then I see an inline error and cannot proceed.
- **AC3**: Given the CP module is unreachable (5xx / timeout), when the call fails, then I see a "Service unavailable, retry" message — no partial state is saved.
- **AC4**: Given covenants are already applied to this CP, when I look it up, then I see a banner "X covenants already applied — continue to add more, or view existing".

**Definition of Done**: `CreditProposalClient.lookup(cpId)` + wizard Step 1 + error states.

---

### US-011 — Fetch CP attributes for filtering
**Persona**: P5 System · **Priority**: P0

**Story**: As the system, I want to fetch a CP's attribute map from the external Attributes API so that I can filter the library down to relevant covenants.

**Acceptance Criteria**
- **AC1**: Given a valid `CP_ID`, when `CovenantAttributesClient.fetch(cpId)` is called, then it returns a Map of {businessSegment, sector, …}.
- **AC2**: Attribute results are cached for the duration of the wizard session (in `CreditProposalCache` table) so that subsequent recommendation calls don't re-hit the API.
- **AC3**: Cache TTL is 1 hour; after expiry, attributes are re-fetched.
- **AC4**: WireMock stub returns realistic attribute payloads for at least 3 sample CPs covering different segments.

**Definition of Done**: Client interface + WebClient impl + WireMock mappings + cache table.

---

### US-012 — Get recommended covenants for a CP
**Persona**: P2 · **Priority**: P0

**Story**: As a Credit Operations Officer, I want the system to suggest a starting set of covenants based on the CP's attributes, so that I don't have to scan the entire library.

**Acceptance Criteria**
- **AC1**: Given a CP with `businessSegment=RETAIL`, when I hit `GET /api/v1/credit-proposals/{cpId}/recommendations`, then I receive all `active` covenants whose `businessSegments` is empty (universal) **or** contains `RETAIL`.
- **AC2**: Each recommendation row includes a `matchReason` string listing which attribute(s) matched.
- **AC3**: Recommendations are persisted to `CovenantRecommendation` table with status `SUGGESTED`.
- **AC4**: Re-running the recommendation for the same CP returns the same logical set without duplicating rows (idempotent).
- **AC5**: The UI displays the suggestion list with all rows pre-checked.

**Definition of Done**: `RecommendationService.recommendForCp` + endpoint + Wizard Step 2 UI.

---

### US-013 — Override recommendation (uncheck suggested covenants)
**Persona**: P2 · **Priority**: P0

**Story**: As a Credit Operations Officer, I want to uncheck covenants from the suggested list that don't apply to this specific CP, so that the monitoring scope reflects business reality.

**Acceptance Criteria**
- **AC1**: Each suggested row has a checkbox; unchecking removes it from the "to apply" list locally (no API call yet).
- **AC2**: When I save in Step 4, only checked rows become `AppliedCovenant` records.
- **AC3**: Unchecked recommendation rows are persisted with `status=REJECTED` so that a future re-recommendation can show "previously rejected" for transparency.

**Definition of Done**: Wizard local state + payload construction + recommendation status update.

---

### US-014 — Add ad-hoc covenants from the full library
**Persona**: P2 · **Priority**: P0

**Story**: As a Credit Operations Officer, I want to add covenants that weren't in the suggestion list by browsing the master library, so that I can handle exceptional cases not captured by the attribute filter.

**Acceptance Criteria**
- **AC1**: An "Add from library" button opens a `CovenantPickerModal` listing all `active` covenants not already in the current selection.
- **AC2**: I can search and filter within the modal (same controls as the master list).
- **AC3**: Selecting covenants and clicking "Add" appends them to the wizard list with `recommendationStatus=MANUAL_ADD`.
- **AC4**: Manually-added covenants are visually distinguishable (e.g. a "Manual" tag) in the wizard.

**Definition of Done**: Modal component + state merge + status tagging.

---

## Epic 4 — Frequency Configuration

### US-015 — Set frequency per applied covenant
**Persona**: P2 · **Priority**: P0

**Story**: As a Credit Operations Officer, I want to set a monitoring frequency (Daily / Weekly / Monthly / Quarterly / Yearly) on each covenant before activating, so that the system assesses each at the right cadence.

**Acceptance Criteria**
- **AC1**: Every row in Step 3 has a `FrequencyPicker` defaulting to `MONTHLY`.
- **AC2**: Given I pick `DAILY`, when I save, then the row persists with `frequency=DAILY` and Quartz cron `0 0 2 * * ?` (2am daily).
- **AC3**: Frequencies map to fixed crons: `DAILY=0 0 2 * * ?`, `WEEKLY=0 0 2 ? * MON`, `MONTHLY=0 0 2 1 * ?`, `QUARTERLY=0 0 2 1 1,4,7,10 ?`, `YEARLY=0 0 2 1 1 ?`.
- **AC4**: I cannot save the wizard with any covenant missing a frequency.

**Definition of Done**: Enum + cron mapper + FrequencyPicker component + form validation.

---

### US-016 — Use custom cron for non-standard frequencies
**Persona**: P2 · **Priority**: P1

**Story**: As a Credit Operations Officer, I want to specify a custom cron expression for unusual cadences (e.g. "first Monday of each quarter"), so that covenants with irregular review cycles can be monitored.

**Acceptance Criteria**
- **AC1**: The `FrequencyPicker` has a "Custom (cron)" option that reveals a text input.
- **AC2**: Given I enter an invalid cron, when I leave the field, then I see an inline error from a server-side validator (`POST /api/v1/schedules/validate-cron`).
- **AC3**: Custom cron is persisted alongside `frequency=CUSTOM` and `cronExpression=<entered>`.

**Definition of Done**: Cron validation endpoint (uses Quartz `CronExpression.isValidExpression`) + UI custom input.

---

### US-017 — Update frequency on an active covenant
**Persona**: P2 · **Priority**: P0

**Story**: As a Credit Operations Officer, I want to change the frequency of an already-active covenant, so that I can respond to risk-level changes without re-onboarding.

**Acceptance Criteria**
- **AC1**: Given an `AppliedCovenant` is `ACTIVE`, when I `PATCH /api/v1/applied-covenants/{id}` with a new frequency, then the existing Quartz trigger is unscheduled and replaced with one matching the new cron.
- **AC2**: The next-run timestamp recomputes immediately.
- **AC3**: Past assessments remain attached to the same applied-covenant id — frequency change does not orphan history.
- **AC4**: An audit log entry captures `oldFrequency → newFrequency`, `changedBy`, `changedAt`.

**Definition of Done**: Patch endpoint + Quartz rescheduling + audit row.

---

### US-018 — Suspend and resume an applied covenant
**Persona**: P2 · **Priority**: P1

**Story**: As a Credit Operations Officer, I want to temporarily suspend assessments on a covenant (e.g. during a CP restructuring) and resume later, so that no spurious breaches are recorded during transition.

**Acceptance Criteria**
- **AC1**: A "Suspend" action on the CP detail page sets `status=SUSPENDED` and pauses the Quartz trigger (does not delete it).
- **AC2**: While suspended, no assessments run; the UI shows a yellow "Suspended" chip.
- **AC3**: Resuming sets `status=ACTIVE` and resumes the trigger; the next run picks up the original cron.
- **AC4**: Suspend/resume actions append to the same audit log used by US-017.

**Definition of Done**: Patch endpoint + Quartz pause/resume + UI actions.

---

## Epic 5 — Monitoring Schedule

### US-019 — Create monitoring schedule on covenant application
**Persona**: P5 System · **Priority**: P0

**Story**: As the system, when a covenant is applied to a CP with a chosen frequency, I want to create a durable Quartz schedule so that assessments run automatically without manual intervention.

**Acceptance Criteria**
- **AC1**: Given the wizard submits N applied-covenants, when persistence completes, then N rows exist in `monitoring_schedule` and N `QRTZ_TRIGGERS` rows exist.
- **AC2**: Each schedule's `quartzJobKey` is deterministic: `assessment-<appliedCovenantId>`.
- **AC3**: `nextRunAt` is computed from the cron expression and persisted on the schedule row.

**Definition of Done**: `ScheduleService.createSchedule` + Quartz JDBC store config + transactional consistency between app rows and Quartz rows.

---

### US-020 — Schedule survives system restart (durability)
**Persona**: P5 System · **Priority**: P0

**Story**: As the system, I want schedules to persist across application restarts, so that nothing is lost during deployments.

**Acceptance Criteria**
- **AC1**: Given a schedule exists with `nextRunAt` in the future, when I restart the Spring Boot app, then the same trigger fires at the original `nextRunAt`.
- **AC2**: Given a schedule's `nextRunAt` passed while the app was down, when the app starts, then the assessment fires once on startup per the misfire policy (`MISFIRE_INSTRUCTION_FIRE_NOW`).

**Definition of Done**: Quartz JDBC job store + integration test that stops/starts the app and asserts trigger persistence.

---

## Epic 6 — Automated Assessment Execution

### US-021 — Run scheduled assessment at frequency tick
**Persona**: P5 System · **Priority**: P0

**Story**: As the system, when a scheduled tick fires, I want to run an assessment for the bound `AppliedCovenant` so that compliance is evaluated automatically.

**Acceptance Criteria**
- **AC1**: When `AssessmentJob.execute` fires for `appliedCovenantId=X`, an `Assessment` row is created with `status=IN_PROGRESS` and `scheduledAt=<trigger fire time>`.
- **AC2**: The job loads the linked `CovenantMaster`, calls Data API, evaluates, persists result, calls workflow, and sets `status=COMPLETED`.
- **AC3**: If any step fails, the assessment row is updated to `status=COMPLETED` and `result=ERROR` with the exception captured in a dedicated `errorMessage` column.
- **AC4**: The schedule's `lastRunAt` updates regardless of compliant/breached/error outcome.

**Definition of Done**: `AssessmentJob` Quartz Job + transactional service method + error capture.

---

### US-022 — Evaluate rule against current data
**Persona**: P5 System · **Priority**: P0

**Story**: As the system, I want each assessment to evaluate the covenant rule against current values fetched from the Data API, so that the result reflects the present state.

**Acceptance Criteria**
- **AC1**: Given the rule references metric keys M and threshold keys T, when the assessment runs, then a single Data API call is made with `keys = M ∪ T` and `asOfDate = today`.
- **AC2**: The full response is persisted as `dataSnapshotJson` on the assessment.
- **AC3**: The SpEL evaluator runs with all variables bound; result is `COMPLIANT` (true), `BREACHED` (false), or `ERROR` (exception / non-boolean).

**Definition of Done**: `RuleEvaluator` per Rule Evaluation Deep Dive section.

---

### US-023 — Handle missing data gracefully
**Persona**: P5 System · **Priority**: P0

**Story**: As the system, when the Data API can't supply one of the required keys, I want to record an `ERROR` outcome with the missing keys listed, so that a data-quality problem is not silently misclassified as a breach.

**Acceptance Criteria**
- **AC1**: Given Data API response `missingKeys=['threshold_DSCR']`, when the evaluator runs, then `result=ERROR` and `errorMessage="Missing values: [threshold_DSCR]"`.
- **AC2**: No SpEL evaluation is attempted when `missingKeys` is non-empty (short-circuit).
- **AC3**: The workflow is still kicked off so that an assessor can investigate the data gap.

**Definition of Done**: Short-circuit in `RuleEvaluator` + workflow invocation regardless of result type.

---

### US-024 — Capture data snapshot per assessment
**Persona**: P4 Compliance · **Priority**: P0

**Story**: As a Compliance Auditor, I want every assessment to record the exact data values it evaluated against, so that I can prove what the system "knew" at evaluation time.

**Acceptance Criteria**
- **AC1**: `Assessment.dataSnapshotJson` contains the full `values` map returned by the Data API for that run.
- **AC2**: The snapshot is immutable after the assessment is completed.
- **AC3**: The snapshot is shown verbatim in the Assessment Detail page as a key-value table.

**Definition of Done**: JSON column + read-only enforcement at service layer + Detail UI.

---

### US-025 — Render evaluated expression with literals for audit
**Persona**: P4 · **Priority**: P0

**Story**: As a Compliance Auditor, I want each assessment to store a human-readable resolved expression (e.g. `1.85 > 1.7`), so that I can see the conclusion without joining to the snapshot.

**Acceptance Criteria**
- **AC1**: For `#DSCR > #threshold_DSCR` with `{DSCR:1.85, threshold_DSCR:1.7}`, the stored `evaluatedExpression = "1.85 > 1.7"`.
- **AC2**: String values are single-quoted in the resolution: `'KPMG' == 'E&Y'`.
- **AC3**: Dates are formatted as ISO-8601: `2026-06-04 > 2025-06-04`.
- **AC4**: `null` values render as the literal `null`.

**Definition of Done**: `renderResolved(spel, values)` utility + unit tests for each type.

---

### US-026 — Manually trigger an assessment
**Persona**: P2 / P3 · **Priority**: P1

**Story**: As an Operations Officer or Assessor, I want to run an ad-hoc assessment outside the schedule, so that I can immediately re-evaluate after a known data change.

**Acceptance Criteria**
- **AC1**: A "Run now" button on the CP Detail page calls `POST /api/v1/assessments/run?appliedCovenantId=X`.
- **AC2**: The endpoint enqueues the same job logic as the scheduled path; the `Assessment` row is marked with `triggeredBy=MANUAL` and the user's identity in `triggeredByUser`.
- **AC3**: After completion, the UI auto-refreshes (TanStack invalidation) and shows the new assessment.
- **AC4**: Manual runs do not change the next scheduled run time.

**Definition of Done**: Endpoint + `triggeredBy` column + UI button + mutation.

---

## Epic 7 — Workflow Integration

### US-027 — Kick off workflow on each assessment
**Persona**: P5 / EXT-WF · **Priority**: P0

**Story**: As the system, after each assessment completes, I want to create a workflow instance carrying the assessment's outcome, so that a human assessor can review and act.

**Acceptance Criteria**
- **AC1**: After persisting an `Assessment` row, `WorkflowClient.startInstance(payload)` is called with `{assessmentId, cpId, covenantName, result, evaluatedExpression}`.
- **AC2**: The returned `workflowInstanceId` is persisted onto the assessment.
- **AC3**: If the workflow service returns 5xx, the assessment is not rolled back — instead `workflowInstanceId=null` and a `workflowError` column captures the failure. A separate retry job (out of MVP scope; mention in plan) is a follow-up.
- **AC4**: If workflow returns within a 5-second timeout, persist the ID; otherwise mark `workflowError="timeout"`.

**Definition of Done**: WebClient with timeout + non-transactional invocation pattern + WireMock stub returning a sample ID.

---

### US-028 — Link back to workflow instance from assessment detail
**Persona**: P3 / P4 · **Priority**: P1

**Story**: As an Assessor or Auditor, I want a clickable link from the assessment detail page to the corresponding workflow instance, so that I can navigate to the human-review record.

**Acceptance Criteria**
- **AC1**: When `workflowInstanceId` is non-null, the Assessment Detail page renders a link to `{workflowBaseUrl}/instances/{id}` in a new tab.
- **AC2**: When `workflowInstanceId` is null and `workflowError` is set, an inline alert displays the error.

**Definition of Done**: UI link + config-driven base URL.

---

## Epic 8 — Monitoring Dashboard

### US-029 — View all CPs with covenants at a glance
**Persona**: P3 Credit Risk Assessor · **Priority**: P0

**Story**: As a Credit Risk Assessor, I want a dashboard listing every CP under monitoring with counts of compliant / breached / pending covenants, so that I can prioritise my attention.

**Acceptance Criteria**
- **AC1**: `/monitoring` shows a card or row per CP with: `CP_ID`, business segment, totals {applied, compliant, breached, error}.
- **AC2**: Counts reflect the **latest** assessment per applied covenant — not the cumulative history.
- **AC3**: Rows with any breach are visually emphasised (red top border or chip).
- **AC4**: I can sort by breach count desc to focus on troubled CPs.
- **AC5**: A status filter (All / Has-Breaches / Compliant-Only) narrows the list.

**Definition of Done**: `GET /api/v1/monitoring/summary` aggregating from `assessment` join `applied_covenant` + dashboard UI.

---

### US-030 — Drill down into a CP's covenants
**Persona**: P3 · **Priority**: P0

**Story**: As a Credit Risk Assessor, I want to click a CP and see all covenants applied to it with their latest result and next-run time, so that I have one-screen visibility.

**Acceptance Criteria**
- **AC1**: `/monitoring/{cpId}` shows a table: covenant name, type, frequency, status, latest result, last run, next run, actions (Run now / Suspend).
- **AC2**: Each row's latest-result chip uses the `ResultBadge` color coding.
- **AC3**: A link on each row opens the latest Assessment Detail.

**Definition of Done**: `GET /api/v1/credit-proposals/{cpId}/monitoring-detail` + CP Detail page.

---

### US-031 — View assessment history for an applied covenant
**Persona**: P3 / P4 · **Priority**: P0

**Story**: As an Assessor or Auditor, I want to see the full chronological history of assessments for a single applied covenant, so that I can identify trends (e.g. drifting toward breach) and audit historical compliance.

**Acceptance Criteria**
- **AC1**: From the CP Detail row, "View history" opens a table sorted by `scheduledAt` desc.
- **AC2**: Each historical row shows date, result, resolved expression, workflow link.
- **AC3**: History is paginated; default 25 per page; oldest available without limit.

**Definition of Done**: `GET /api/v1/applied-covenants/{id}/assessments?page=&size=` + History UI.

---

### US-032 — Filter monitoring view by result status
**Persona**: P3 · **Priority**: P1

**Story**: As an Assessor, I want to filter assessments by result (COMPLIANT / BREACHED / ERROR / PENDING_REVIEW), so that I can isolate the ones needing action.

**Acceptance Criteria**
- **AC1**: A multi-select chip filter applies across the dashboard summary and CP detail pages.
- **AC2**: Filter state is persisted in the URL query string so views are shareable.

**Definition of Done**: Query param plumbing + UI filter component.

---

## Epic 9 — External Integrations

### US-033 — Mock external APIs via WireMock for development
**Persona**: P1 / P2 developer · **Priority**: P0

**Story**: As a developer, I want WireMock stubs for the CP module, attributes API, data API, and workflow service so that I can run the full system locally without depending on any external team.

**Acceptance Criteria**
- **AC1**: With the `dev` Spring profile active, a WireMock server starts on port 9311 with mappings from `classpath:wiremock/`.
- **AC2**: Stubs cover at least 3 sample CPs (different segments), 5 metric/threshold keys, and a passing workflow create response.
- **AC3**: WireMock recording mode can be enabled via config to capture real responses when a downstream team's API becomes available.

**Definition of Done**: WireMock auto-config bean (dev only) + stub JSON files in resources + README note.

---

### US-034 — Configurable base URLs per environment
**Persona**: Ops · **Priority**: P0

**Story**: As an Operations engineer, I want all external API base URLs to be configurable per environment (dev/test/prod), so that switching from WireMock to real services is config-only.

**Acceptance Criteria**
- **AC1**: `application.yml` exposes `covenant.clients.{credit-proposal|attributes|data-api|workflow}.base-url`.
- **AC2**: WebClient beans read base URLs at startup via `@ConfigurationProperties`.
- **AC3**: Overriding via env var works (Spring relaxed binding): `COVENANT_CLIENTS_DATAAPI_BASEURL=https://...`.

**Definition of Done**: Properties class + WebClient factory + sample env-var override in `application-prod.yml`.

---

## Epic 10 — Audit & Compliance

### US-035 — Maintain immutable audit trail of covenant edits
**Persona**: P4 · **Priority**: P0

**Story**: As a Compliance Auditor, I want every modification to a covenant master rule recorded with who, when, before, and after, so that I can evidence rule-change governance.

**Acceptance Criteria**
- **AC1**: Every `PUT /api/v1/covenants/{id}` writes a row to `expression_change_log` (when the expression specifically changes) plus a generic `covenant_audit_log` for any field change.
- **AC2**: Audit rows cannot be edited or deleted via any API — read-only after insert.
- **AC3**: A `GET /api/v1/covenants/{id}/audit` endpoint returns the full change history.

**Definition of Done**: Audit tables + JPA Envers OR custom audit listener + read endpoint + DB-level revoke on writes for the audit tables (deferred to ops hardening).

---

### US-036 — Persistent assessment history
**Persona**: P4 · **Priority**: P0

**Story**: As a Compliance Auditor, I want assessment records to persist indefinitely without overwrite, so that historical compliance is always provable.

**Acceptance Criteria**
- **AC1**: There is no `DELETE` endpoint on assessments.
- **AC2**: Schema does not allow updating `result`, `dataSnapshotJson`, `evaluatedExpression`, or `executedAt` after `status=COMPLETED` (enforced at service layer).
- **AC3**: A documented archival strategy exists for moving assessments older than X years to cold storage (post-MVP, mentioned in non-goals).

**Definition of Done**: Service-layer immutability check + integration test asserting update is blocked.

---

### US-037 — Identify who triggered a manual run
**Persona**: P4 · **Priority**: P1

**Story**: As a Compliance Auditor, I want manual assessment runs to record the user who triggered them, so that I can distinguish system-driven from human-driven evaluations.

**Acceptance Criteria**
- **AC1**: `Assessment.triggeredBy` is either `SCHEDULED` or `MANUAL`.
- **AC2**: When `MANUAL`, `triggeredByUser` captures the authenticated principal's identity (assumed to arrive via gateway header for v1).
- **AC3**: The Assessment Detail page surfaces both fields.

**Definition of Done**: Columns + controller picks up identity from `SecurityContextHolder` or request header `X-User` (gateway-managed) + UI display.

---

## Cross-Cutting / Non-Functional Stories

### US-038 — All API errors return a consistent envelope
**Priority**: P0

**Story**: As a frontend developer, I want every error response to follow a single shape `{code, message, details}`, so that error handling is uniform.

**Acceptance Criteria**: Global `@ControllerAdvice` translates `MethodArgumentNotValidException`, `ConstraintViolationException`, business `ApiException`, and uncaught `Exception` into the envelope.

---

### US-039 — H2 in MSSQL mode behaves like prod
**Priority**: P0

**Story**: As a developer, I want H2 configured in MSSQL compatibility mode with Flyway-managed schema, so that local dev exposes any prod-schema incompatibility early.

**Acceptance Criteria**: `application.yml` includes `MODE=MSSQLServer`; Flyway migrations use MSSQL-compatible syntax (no H2-specific features); JPA dialect is `SQLServerDialect`.

---

### US-040 — Swagger UI for API exploration
**Priority**: P1

**Story**: As a developer or integrator, I want a Swagger/OpenAPI UI to explore endpoints, so that contract review and ad-hoc testing don't require Postman setup.

**Acceptance Criteria**: `/swagger-ui.html` serves a complete spec auto-generated by springdoc; all endpoints have @Tag and @Operation metadata.

---

## Story Map Summary (priority view)

| Epic | P0 stories | P1 stories | P2 stories |
|---|---|---|---|
| Covenant Master Library | US-001, 002, 003, 004 | US-005 | — |
| Rule Authoring & Validation | US-006, 007, 008, 009 | — | — |
| CP Onboarding & Recommendation | US-010, 011, 012, 013, 014 | — | — |
| Frequency Configuration | US-015, 017 | US-016, 018 | — |
| Monitoring Schedule | US-019, 020 | — | — |
| Automated Assessment | US-021, 022, 023, 024, 025 | US-026 | — |
| Workflow Integration | US-027 | US-028 | — |
| Monitoring Dashboard | US-029, 030, 031 | US-032 | — |
| External Integrations | US-033, 034 | — | — |
| Audit & Compliance | US-035, 036 | US-037 | — |
| Cross-cutting | US-038, 039 | US-040 | — |

**MVP scope = all P0 stories** (≈ 28 stories). P1 stories close out v1 release. P2 captured as backlog candidates.

---

## Open Questions Tracked for PO Decision

1. **Authentication model** — gateway-injected `X-User` header or full Spring Security with JWT? (Currently assumed: gateway.)
2. **Notification on breach** — owned by workflow service (per plan) or do we add native email/Slack?
3. **Cron timezone** — UTC system-wide or per-CP timezone? (Currently assumed: UTC, displayed in user's locale.)
4. **Misfire policy** — fire immediately on startup (current) or skip and wait for next? (Currently: fire immediately.)
5. **Bulk CP onboarding** — out of scope, but is there a near-term CSV import expectation?
