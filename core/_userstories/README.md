# User Stories — Covenant configuration & monitoring

Epics:
- **Epic A — eligibility, classification & assessment type:** extend the **Covenant** definition so
  the system recommends the right covenants per credit proposal (CP), and add an **assessment type**
  (Auto vs Manual) that decides whether a covenant is evaluated by a formula or by a person.
- **Epic B — monitoring scheduling & visibility:** give each covenant its own frequency and start
  date (overridable per CP), and replace the status-only monitoring screen with a time-aware view of
  what is overdue, due soon, upcoming, and starting soon across all the user's CPs.
- **Epic C — data source & measure modeling:** broaden the kinds of source tables measures can be
  read from, so covenants can be assessed against real customer data in different physical shapes.
- **Epic D — formula & assessment semantics:** control *how* a windowed aggregation samples the
  time series — a flat average of every reading vs one period-end figure per period — with
  quarter/year boundaries following the organisation's calendar or fiscal year.
- **Epic E — ML-assisted configuration:** let an ML service recommend covenant thresholds for a CP
  during onboarding (preview + confirm; only changed thresholds are overridden), and record the
  provenance of every applied threshold plus an append-only audit of every ML recommendation.
- **Epic F — RM assessment, disposition & evidence:** turn the read-only monitoring screens into an
  action workspace — after the daily breach email the RM lands on a unified By-CP / By-Covenant view,
  drills into a CP, works a covenant assessment grid (expression, actual, headroom, status), records a
  **Waiver/Extension** disposition on breaches, attaches evidence + a compliance certificate, pulls
  optional **AI-suggested** statuses, and has every status change audited with provenance.

## Background / problem
Today a covenant is recommended for a CP using only `businessSegments`; banks need much richer
targeting (Shariah compliance, industry, geography, deal/entity/facility/collateral type), and many
covenants are qualitative checks an analyst verifies manually rather than formula-evaluated. And the
monitoring screen shows only **status counts** — it can't tell a user *what they must act on and
when*: with dozens of covenants on different cadences and start dates, upcoming and overdue
monitoring dates are invisible.

## Stories

### Epic A — Covenant eligibility, classification & assessment type
| ID | Title | Summary |
|----|-------|---------|
| [US-CM-01](US-CM-01-covenant-eligibility-fields.md) | Covenant eligibility & classification fields | Add Islamic Banking + Segment + multi-selects to the covenant screen; mandatory-field set |
| [US-CM-02](US-CM-02-multiselect-all-wildcard.md) | "All" wildcard semantics | Each multi-select has **All**; All = ignore that dimension when matching |
| [US-CM-03](US-CM-03-assessment-type-auto-manual.md) | Assessment type (Auto / Manual) | Auto → formula builder + validation; Manual → free text, no validation |
| [US-REC-04](US-REC-04-recommendation-matching.md) | Recommendation matching by eligibility | Match CP attributes against covenant eligibility; All = wildcard |
| [US-ASM-05](US-ASM-05-manual-assessment-execution.md) | Manual assessment execution | Manual covenants produce a person-entered result, not auto-evaluated |
| [US-DATA-06](US-DATA-06-reference-data-and-naming.md) | Reference data & `cov_` table naming | Data-driven dropdown catalogs (API-populated); all new tables prefixed `cov_` |
| [US-CM-22](US-CM-22-cp-scoped-custom-covenant.md) | CP-scoped custom covenant | Add a one-off covenant to a single CP at onboarding; never enters the shared library; eligibility copied from the CP |
| [US-CM-23](US-CM-23-formula-builder.md) | Formula Builder (AUTO condition authoring) | Consolidated spec of the assisted AUTO-condition editor: measure autocomplete, live validation + sample dry-run, syntax highlight/guide, sampling control, threshold extract/apply |
| [US-CM-24](US-CM-24-custom-covenant-assessment-type.md) | Assessment type for a CP-scoped custom covenant | Custom covenant modal gains **Auto / Manual** (defaults Auto); Auto → Formula Builder, validated formula, extracted measures, Financial; Manual → today's free text |
| [US-CM-25](US-CM-25-per-cp-covenant-definition-and-audit.md) | Per-CP covenant definition & definition change audit | Configure step edits the **whole** condition per CP in the Formula Builder (measures included — the `INVALID_THRESHOLD_OVERRIDE` guard is removed); every committed change audited and shown as a Definition change log on CP detail |

### Epic B — Monitoring scheduling & visibility
| ID | Title | Summary |
|----|-------|---------|
| [US-FREQ-07](US-FREQ-07-covenant-frequency-and-start-date.md) | Per-covenant frequency + per-CP override | Covenant default frequency; Configure overrides frequency **and** start date per CP |
| [US-MON-08](US-MON-08-cp-covenant-monitoring-timeline.md) | Per-covenant monitoring timeline (CP screen) | Each covenant shows start (passed/upcoming), next due, last assessed, timing status |
| [US-MON-09](US-MON-09-portfolio-monitoring-schedule.md) | Portfolio monitoring schedule & calendar | One time-ordered view of overdue / due-soon / upcoming / starting-soon across all CPs |
| [US-MON-14](US-MON-14-daily-breach-run-and-notifications.md) | Daily breach-assessment run & RM/WRM notifications | Daily/on-demand sweep assesses every due covenant, emails each RM (cc WRM) one per-CP summary merged by (RM, WRM) pair; resilient + fully audited |

### Epic C — Data source & measure modeling
| ID | Title | Summary |
|----|-------|---------|
| [US-DS-10](US-DS-10-row-key-value-data-source.md) | Row / key-value (EAV) data source layout | Support tables where one value column holds many measures keyed by a code column, alongside columnar tables |
| [US-DS-11](US-DS-11-data-source-onboarding-journey.md) | Data-source onboarding journey (layout UI, staging copy, obligor→CP & scale) | UI to register tables (columnar/row + CP/obligor level); durable `cov_staging_value` copy the resolver reads for REST+MSSQL; obligor→CP fan-out; scalable daily run |
| [US-DS-12](US-DS-12-multi-year-rest-series-fetch.md) | Multi-year REST series fetch | `fetchSeries` pulls many dated points in one ingest so multi-year windows resolve on first run; range auto-derived from the covenant window; incremental fetch (builds on US-DS-11) |
| [US-DS-13](US-DS-13-freshness-gated-ingestion-orchestration.md) | Freshness-gated, due-driven ingestion orchestration | Ingest once per entity ahead of need, only when the staged copy is missing/stale; due-driven + freshness + calendar triggers; coalesced entity-grain extracts; no per-covenant fetch, no blind daily full pull |

### Epic D — Formula & assessment semantics
| ID | Title | Summary |
|----|-------|---------|
| [US-AGG-11](US-AGG-11-period-aware-windowed-aggregation.md) | Period-aware windowed aggregation | Optional `PERIODIC` sampling collapses each period to its period-end figure before aggregating; org calendar/fiscal boundaries + a trailing-period inclusion policy |
| [US-ASM-21](US-ASM-21-decomposed-assessment-comparisons.md) | Decomposed per-comparison assessment values | Store measure/operator/threshold/actual per comparison in `cov_assessment_threshold` (one row per comparison) at assessment time |

### Epic E — ML-assisted configuration
| ID | Title | Summary |
|----|-------|---------|
| [US-ML-12](US-ML-12-ml-recommended-thresholds.md) | ML-recommended thresholds in CP Onboarding | Button calls a (mock, resilient) ML API; preview + confirm; override only changed thresholds |
| [US-ML-13](US-ML-13-threshold-provenance-and-audit.md) | Threshold provenance & recommendation audit trail | Tag each applied threshold MASTER/USER/ML; append-only log of every ML recommendation + applied decision |
| [US-ML-20](US-ML-20-auto-master-and-ml-recommendations-on-lookup.md) | Auto master + ML recommendations on Lookup "Next" | ML runs automatically after master recs (button removed); may override a master threshold or add a new covenant, tagged ML in the audit trail |

### Epic F — RM assessment, disposition & evidence
| ID | Title | Summary |
|----|-------|---------|
| [US-MON-15](US-MON-15-daily-monitoring-workspace.md) | Daily monitoring workspace (By-CP / By-Covenant) | One landing screen merging the US-MON-14 summary + US-MON-09 agenda via a toggle; drill into a CP to assess |
| [US-MON-16](US-MON-16-cp-assessment-grid-disposition.md) | CP-level assessment grid & Waiver/Extension disposition | Per-CP grid (expression, actual, headroom, status); status derived from result; Breached ⇒ Waiver/Extension (Extension ⇒ date) |
| [US-MON-17](US-MON-17-evidence-attachments-and-compliance-certificate.md) | Evidence attachments & CP compliance certificate | Upload evidence per covenant + a CP-level compliance certificate through a resilient external file service; store fileIds |
| [US-MON-18](US-MON-18-ai-status-suggestions.md) | AI-assisted covenant status suggestions | "AI Suggestion" calls a (resilient) external AI API with covenant data + fileIds; preview per-covenant statuses; user applies/overrides |
| [US-MON-19](US-MON-19-status-provenance-and-audit.md) | Covenant status provenance & disposition audit trail | Tag each status SYSTEM/USER/AI; append-only `cov_covenant_status_audit` of every change — mirrors US-ML-13 |
| [US-MON-20](US-MON-20-disposition-aware-by-cp-view.md) | By-CP view surfaces handled (Waived/Extended) breaches | Keep the Breached count; add a "handled" count so a row reads "Breached 4 (2 handled)" — active disposition only, no `cov_assessment` change |
| [US-MON-21](US-MON-21-by-covenant-agenda-disposition-badges.md) | By-Covenant agenda badges dispositioned events | Carry effective disposition onto `MonitoringEvent`; Waiver/Extension badge + optional "Hide handled"; timing bucket unchanged |
| [US-MON-22](US-MON-22-disposition-refreshes-portfolio-views.md) | Recording a disposition refreshes the portfolio views live | `DispositionModal` `onSuccess` also invalidates the summary + schedule queries so By-CP/By-Covenant refetch (frontend only; ship with 20/21) |
| [US-MON-23](US-MON-23-linked-cp-cycle-history.md) | Read-only historical cycle viewer for linked CPs | Walk `parentCpId` lineage; full-screen viewer shows each earlier CP as Cycle 1…current, read-only mirror of the US-MON-16 grid + US-MON-17 certificates; file **download** only (no upload/remove/disposition/AI) |

## Glossary
- **Covenant (master)** — a reusable covenant template in the library (`covenant_master`).
- **Applied covenant** — a covenant attached to one CP (`applied_covenant`), optionally with a
  per-CP condition override (the whole definition, US-CM-25) and per-CP condition type /
  classification.
- **Condition type** — a per-CP tag on an applied covenant: `PRECEDENT` (condition precedent) or
  `SUBSEQUENT` (condition subsequent). Captured at apply time (Configure step, US-FREQ-07); optional
  (defaults to unset). Lives on the applied covenant, not the covenant master.
- **Classification** — a per-CP tag on an applied covenant: `EXTERNAL` or `INTERNAL`. Captured at
  apply time (Configure step, US-FREQ-07); optional (defaults to unset). Lives on the applied
  covenant, not the covenant master.
- **Eligibility dimension** — an attribute used to decide if a covenant fits a CP (Islamic banking,
  segment, industry, country of risk, country of incorporation, credit-proposal type, legal-entity
  type, facility type, collateral type).
- **All** — a synthetic option on a multi-select meaning "applies to any value" (wildcard); that
  dimension is excluded from matching.
- **Assessment type** — `AUTO` (measure formula, machine-evaluated) or `MANUAL` (free-text
  instruction, human-evaluated).
- **Monitoring start date** — the date monitoring begins for an applied covenant (may be future);
  before it, the covenant is "not started".
- **Next due date** — the next scheduled monitoring/assessment date, computed from the covenant's
  cron at/after `max(today, startDate)`.
- **Timing status** — derived state of an applied covenant: *Not started · Up to date · Due soon ·
  Overdue · Ended*.
- **Columnar (wide) layout** — a data-source table where each measure is its own physical column
  (today's default); a measure maps to one `source_column`.
- **Key-value (row / long / EAV) layout** — a data-source table where one *value* column holds many
  measures, identified by a *code* column; a measure maps to a `source_code` matched against that
  code column.
- **Entity-key level** — the grain a data-source table is keyed at: `cp_id` (default) or `obligor_id`;
  an obligor-keyed table's data is resolved once per obligor and fanned out to that obligor's CPs
  (US-DS-11).
- **Durable staging copy** — a stored, repeatable copy of a source's data landed into
  `cov_staging_value` (long/EAV, keyed by table + entity + as-of + item code) that the assessment
  engine reads for **both** REST and MSSQL sources, instead of querying the live source per
  assessment; distinct from the immutable per-assessment `data_snapshot_json` audit copy (US-DS-11).
- **JIT (just-in-time) ingestion** — the assessment-time, idempotent (delete-then-insert) copy of a
  source's rows into `cov_staging_value`, run once per `(table, entity, as-of)` (US-DS-11).
- **Obligor→CP mapping** — a **one obligor → many CPs** relationship (`cov_obligor_cp_map`, unique on
  cp_id, indexed by obligor_id) captured from the `obligor_id` returned at CP lookup; lets
  obligor-grain data fan out to each CP that executes covenants (US-DS-11).
- **Freshness gate** — the per-`(entity, source)` decision to ingest only when the staged copy is
  missing, older than the covenants' `required_as_of`, or superseded by a newer `source_changed_at`;
  otherwise skip. Replaces the blind daily full pull (US-DS-13).
- **Due-driven ingestion** — running the freshness gate only against entities with a covenant due
  inside a **lookahead horizon**, so data is pre-staged just ahead of evaluation rather than fetched
  per covenant execution (US-DS-13).
- **Ingestion watermark** — the `MAX(as_of_date)` staged per `(data_source_table_id, entity_key)`
  (optionally tracked in a `cov_ingestion_state` control table); the cursor for both the freshness
  gate and incremental (delta) fetching (US-DS-13).
- **Coalesced extract** — collapsing all due-and-stale covenants on an entity into a single source
  fetch: one `(entity, source)` call covering the union of required `item_code`s over the widest
  window, obligor-grain first then CP (US-DS-13).
- **Sampling mode** — per-aggregation choice of how a window is reduced: `FLAT` (every reading —
  today's default) or `PERIODIC` (one period-end figure per period, then aggregate).
- **FLAT** — the default sampling mode: aggregate every reading whose as-of date falls in the rolling
  window (unchanged legacy behaviour).
- **PERIODIC (period-end)** — sampling mode that first collapses each period in the window to its
  *period-end* value (the latest reading dated ≤ as-of within that period), then aggregates those
  figures; period granularity is inferred from the window unit (`M`/`Q`/`Y`).
- **Reporting period** — a calendar-aligned bucket (month, quarter, or year) used by PERIODIC
  sampling; `Q`/`Y` boundaries follow the org's fiscal-year-start month.
- **Completed period** — a reporting period whose end date is ≤ the as-of date; contrast the
  *in-progress* period, which contains the as-of date but has not yet ended.
- **Trailing-period inclusion** — policy for whether a PERIODIC window counts the in-progress period:
  org default `periodicIncludesCurrentPeriod` (default = include), overridable per formula via
  `PERIODIC_CURRENT` / `PERIODIC_COMPLETED`.
- **Fiscal year start month** — org setting (1–12) that aligns quarter/year period boundaries: `1` =
  calendar year, `4` = an April–March fiscal year.
- **Threshold source** — provenance of an applied covenant's threshold: `MASTER` (library default),
  `USER` (human-edited), or `ML` (accepted ML recommendation).
- **ML recommendation** — a data-driven threshold suggested by the ML service for a covenant on a CP;
  advisory only, applied only after user confirmation.
- **Recommendation event** — an append-only audit record of one ML threshold suggestion (current →
  recommended, confidence, rationale, who, when), logged even when declined.
- **Breach-notification run** — a daily (or on-demand, date-selectable) sweep that assesses every
  ACTIVE covenant due on the run date, then notifies each CP's owners; identified by a `runId` that
  correlates all of the run's audit rows (US-MON-14).
- **RM / WRM** — the **Relationship Manager** and **Wholesale Relationship Manager** tagged on a CP
  (each a user with name + email); notification recipients — RM as `emailTo`, WRM as `emailCC`.
- **(RM, WRM) pair merge** — grouping the run's touched CPs by their (RM email, WRM email) so each
  distinct pair gets exactly one consolidated email listing all their CPs, rather than one email per
  CP.
- **Notification audit** — an append-only `cov_notification_audit` record of one notification attempt
  (request, response, HTTP status, outcome, duration, actor, `runId`); never updated or deleted.
- **Monitoring workspace** — the unified RM landing screen (US-MON-15) with a **By CP / By Covenant**
  toggle that merges the US-MON-14 summary and the US-MON-09 agenda/calendar and drills into a CP's
  assessment grid.
- **Disposition** — an RM's recorded outcome layered on a *Breached* covenant row: `COMPLIED`,
  `BREACHED`, `WAIVER`, or `EXTENSION` (`cov_covenant_disposition`, one current row per CP+covenant).
  It does **not** change `cov_assessment`, the dashboard counts, or US-MON-14 (US-MON-16).
- **Waiver / Extension** — the two dispositions selectable on a breach; **Extension** requires a
  mandatory *extension date*, **Waiver** needs none.
- **Derived status** — the covenant status shown on the grid, computed from the stored assessment
  result (`BREACHED ⇒ Breached`; `COMPLIANT`/`NA ⇒ Complied`); engine semantics unchanged (condition
  true ⇒ COMPLIANT).
- **Headroom** — signed distance from a covenant's threshold in the *compliant* direction (upper-bound:
  threshold − actual; lower-bound: actual − threshold; positive = cushion, negative = breach size);
  shown only for a single numeric comparison, else "—" (US-MON-16).
- **Assessment comparison** — one decomposed comparison of an assessed condition (measure, operator,
  threshold value, actual value, value type) persisted append-only per assessment in
  `cov_assessment_threshold`; an expression with multiple comparisons yields one row each (US-ASM-21).
- **Status source** — provenance of a covenant status/disposition: `SYSTEM` (derived from the stored
  result), `USER` (person-set), or `AI` (accepted AI suggestion) — analogous to threshold source
  (US-MON-19).
- **Evidence attachment** — a file attached to a covenant row (per CP+covenant), uploaded through the
  backend to the external file-upload service and stored as a `fileId` in `cov_covenant_attachment`
  (US-MON-17).
- **Compliance certificate** — CP-level multi-file upload via the same external file service, stored in
  `cov_compliance_certificate` (US-MON-17).
- **AI status suggestion** — an advisory per-covenant status recommended by the external AI service
  from covenant data + uploaded fileIds; applied only on user confirmation and audited with source
  `AI` (US-MON-18).
- **Covenant status audit** — an append-only `cov_covenant_status_audit` record of one status/
  disposition change (old/new status + extension date, source, rationale, actor, when); mirrors
  `ThresholdAudit`, never updated or deleted (US-MON-19).
- **CP lineage** — the chain of linked Credit Proposals for one obligor across renewals, where each CP
  points to its immediate predecessor via `parentCpId` (source is the external CP service, denormalised
  onto `cov_credit_proposal_cache.parent_cp_id`); walked root-ward from an opened CP with cycle/loop
  guards (US-MON-23).
- **Cycle** — one CP's position in its lineage, numbered oldest-first: the root is **Cycle 1** and the
  opened (newest shown) CP is the highest cycle, flagged *current*. The cycle viewer renders each
  cycle's US-MON-16 grid + US-MON-17 certificates strictly read-only, download-only (US-MON-23).

## Cross-cutting rules (apply to every story)
1. **`cov_` table prefix** — any net-new table is named `cov_…` (see project `CLAUDE.md`).
2. **All = wildcard** — when a multi-select is set to **All**, that dimension is omitted from the
   recommendation match (it neither includes nor excludes any CP).
3. **Backward compatibility** — existing covenants (no eligibility data) must keep working: an
   empty/unset multi-select is treated as **All** (wildcard), and existing conditions remain `AUTO`.
4. **Audit & immutability** — eligibility/assessment-type changes on a covenant are audited; past
   assessments remain immutable.
5. **Validation only for AUTO** — formula syntax/measure validation applies only when assessment
   type is `AUTO`; `MANUAL` text is never validated as a formula.
6. **API-driven dropdowns** — **every** eligibility dropdown's options are loaded from the backend
   reference-data API (US-DATA-06); the frontend hardcodes **no** option lists. The covenant form
   fetches all needed catalogs in one call on load. The synthetic **All** item is added
   client-side, not returned by the API. Fixed binary/enum controls (Islamic Banking Yes/No,
   Assessment type Auto/Manual, Category) are static UI choices, not catalog data.

## Assumptions & open questions (confirm with product)
- **Islamic Banking** is modelled as a strict `Yes/No` on the covenant (no "All"), per the request.
  This means a covenant targets either Islamic or conventional deals. *Open:* if some covenants
  apply to **both**, we'd need a third "Any" value — flagged, not assumed.
- **Segment & Industry coexist** — *Resolved:* both are kept as distinct, **mandatory** eligibility
  dimensions. **Segment** is the existing `businessSegments`, now mandatory and catalog-driven
  (category `SEGMENT`); **Industry** is the new dimension (category `INDUSTRY`). Both participate in
  recommendation matching.
- **CP attribute source** — matching needs the CP's values for every dimension; these must be
  exposed by the credit-proposal profile/Data API (see US-REC-04 dependency).
