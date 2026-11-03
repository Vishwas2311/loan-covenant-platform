# Data-Model Cheat-Sheet — one page to hold during the demo

**Read it as:** *user activity → table(s) written → tag/column stamped.* Print this page.

| # | User activity | Screen / service | Table written | Op | Tag / column stamped |
|---|---------------|------------------|---------------|----|----------------------|
| 1 | **Author** a covenant master | Covenant Library → New/Edit · `CovenantMasterService` | `cov_covenant_master` | INSERT | `is_custom=false`, `category` FIN/NON-FIN, `assessment_type` AUTO/MANUAL, `default_frequency` |
| 1e | Edit the master's formula | same | `cov_expression_change_log` | INSERT | `old_expression → new_expression`, `changed_by` |
| 2 | **Recommend** for a CP (rules) | CP → Recommendations · `RecommendationService` | `cov_covenant_recommendation` | UPSERT | **`status=SUGGESTED`** 🔵, `match_reason` |
| 2ml | ML threshold suggestion | same · `MlThresholdService` | `cov_threshold_audit` | INSERT | **`event=RECOMMENDED`, `source=ML`** 🟣, `confidence`, `rationale` |
| 3a | **Accept** a recommendation | Recommendations grid | `cov_covenant_recommendation` | UPDATE | **`status=ACCEPTED`** |
| 3b | **Reject** a recommendation | Recommendations grid | `cov_covenant_recommendation` | UPDATE | **`status=REJECTED`** |
| 3c | **Add custom** covenant | Recommendations grid · `CovenantMasterService` | `cov_covenant_master` + `cov_covenant_recommendation` | INSERT | **`is_custom=true`, `owner_cp_id=CP`, `assessment_type=MANUAL`**; rec **`status=MANUAL_ADD`** |
| 4 | **Apply** covenants to CP | Apply grid · `AppliedCovenantService` | `cov_applied_covenant` | INSERT | `status=ACTIVE`, **`threshold_source`** MASTER ⚪ / USER / ML 🟣, `condition_type`, `classification` |
| 4r | Reconcile on apply | same | `cov_covenant_recommendation` | UPDATE | applied → `ACCEPTED`; leftover `SUGGESTED` → `REJECTED` |
| 4o | Apply with override | same | `cov_threshold_audit` | INSERT | **`event=APPLIED`**, `source=USER/ML` |
| 4e | Later freq/cron/status edit | same | `cov_applied_covenant_audit` | INSERT | `change_type` FREQUENCY/CRON/STATUS, `old→new_value` |
| 5 | **Schedule** *(auto)* | Quartz on apply | `cov_monitoring_schedule` + `QRTZ_*` | INSERT | `status=SCHEDULED`, `cron_expression`, `next_run_at` |
| 6 | **Evaluate** *(auto, per frequency)* | `AssessmentJob` → `AssessmentService` → `FormulaEngine` | `cov_assessment` | **INSERT (immutable)** | **`result`** COMPLIANT 🟢 / BREACHED 🔴 / NA; `triggered_by=SCHEDULED`, `computed_value` |
| 6d | Decompose comparisons | same | `cov_assessment_threshold` | INSERT | `ordinal`, `measure`, `operator`, `threshold_value`, `actual_value` |
| 6n | Daily breach email | breach run (US-MON-14) | `cov_notification_audit` | INSERT | `run_id`, `outcome`, `cp_ids` |
| 7 | **Monitor** at CP level | Monitoring workspace → CP grid · `MonitoringService` | *(none)* | **READ** | status **derived** (BREACHED⇒Breached, COMPLIANT/NA⇒Complied) |
| 8 | **Waiver / Extension** on a breach | Disposition modal · `ReviewService` | `cov_covenant_disposition` | UPSERT | **`status=WAIVER/EXTENSION`** 🟠, `extension_date` (req. for EXTENSION), **`status_source=USER`** (or **AI** 🟦) |
| 8a | Every disposition change | same | `cov_covenant_status_audit` | **INSERT (append-only)** | `old_status→new_status`, `old/new_extension_date`, `source` |
| 8x | Assessment on disposition | — | `cov_assessment` | **UNTOUCHED** | breach stays; dashboard counts unchanged |

## Provenance answer key (what leadership will ask)

- **"Where did this threshold come from?"** → `cov_applied_covenant.threshold_source` = **MASTER / USER / ML**, backed by append-only `cov_threshold_audit`.
- **"Who waived this breach and why?"** → `cov_covenant_disposition` (current) + `cov_covenant_status_audit` (every change, append-only): `changed_by`, `changed_date`, `source`, `rationale`.
- **"Can anyone quietly change a past result?"** → No. `cov_assessment` is immutable (US-036); a re-run makes a new row. Waivers overlay, never overwrite.
- **"What if the ML service is down?"** → The call returns empty (circuit breaker + retry + time limiter); recommendations proceed on rules alone.

## Enum quick-glance

- **Recommendation.status:** SUGGESTED → ACCEPTED / REJECTED · MANUAL_ADD (custom)
- **threshold_source / threshold_audit.source:** MASTER · USER · ML
- **assessment.result:** COMPLIANT · BREACHED · NA · ERROR · PENDING_REVIEW
- **disposition.status:** COMPLIED · BREACHED · WAIVER · EXTENSION
- **disposition.status_source:** SYSTEM · USER · AI

*(Enum values persist lowercase in DB via CHECK constraints; shown uppercase for readability.)*
