# 05: Product Requirements Document — Covenant Platform (Combined)

| | |
|---|---|
| Product | Covenant Platform = Covenant Core (other team) + Document Processing Service (our team) |
| Version | Draft v2, 2026-11-02 |
| Owners | Product owner: TBD. Build: our team builds Core (forked), DPS and the new UI (decision D12); Core changes offered back to the other team |
| Related | [02 architecture](02-target-architecture.md) · [03 flows](03-flows-design.md) · [04 contracts](04-api-contracts.md) · [06 plan](06-implementation-plan.md) |

## 1. Problem
Lenders must monitor the promises (covenants) in every loan agreement, every period, against borrower financials.

- **Covenant Core** monitors well: scheduling, decisions, waivers, audit, RM emails. But each covenant is typed in by hand, and it expects numbers to already exist in bank systems.
- **Our app** reads agreements and statements automatically. But its decision logic, storage and security are not production-grade, with several confirmed defects.

The result today is two half-solutions and manual re-keying, which is slow and error-prone.

## 2. Goals and success metrics

| Goal | Metric | Target (initial, to be agreed) |
|---|---|---|
| G1 Faster onboarding | Median time from agreement upload to covenants applied | −50% vs today's manual entry (baseline to measure) |
| G2 Fewer keying errors | Applied covenants later corrected for wrong threshold | < 2% |
| G3 Automatic assessment from statements | AUTO assessments that complete without manual value entry | ≥ 80% for CPs using uploaded statements |
| G4 One trusted decision | Covenants with two different statuses across screens | 0 |
| G5 Secure | Critical/high security findings open at go-live | 0 |
| G6 Extraction quality | On the golden set: threshold precision / covenant recall | ≥ 95% / ≥ 85% |

## 3. Non-goals (release 1)
- Automatic activation of covenants without human confirmation.
- Amendment/version reconciliation across agreement amendments (release 3).
- Replacing bank core systems, the workflow engine or the notification service.
- Chat and news features (optional, release 3).

## 4. Personas
| Persona | Needs |
|---|---|
| RM / credit officer | Upload documents, confirm covenants, see breaches, request waivers/extensions, attach evidence |
| Approver (credit risk) | Approve waivers/extensions (maker-checker) |
| Covenant author | Maintain library, measures, formulas, mappings |
| DPS reviewer (can be the RM) | Fix low-confidence extractions |
| Support engineer | Diagnose failed jobs and assessments, re-run |
| Auditor | Read-only trace from decision back to document page |

## 5. User journeys
1. **Onboard from agreement:** look up CP → upload agreement → Flow A runs (minutes) → onboarding grid shows EXTRACTED rows with page and quote → RM accepts/edits → apply → schedules created.
2. **Period close:** upload statement with period end → Flow B validates numbers → Core re-assesses (C5) → breach emailed next morning → RM reviews grid → requests extension → approver approves.
3. **Exception:** extraction unclear → job NEEDS_REVIEW → reviewer edits the term with the PDF beside it → publish → continue journey 1.
4. **Manual covenant:** covenant due → PENDING_MANUAL → RM uploads certificate → optional AI suggestion → RM completes with result + note.

## 6. Functional requirements

| ID | Requirement | Acceptance criteria | Owner |
|---|---|---|---|
| FR-01 | Upload agreement / statement / evidence for a CP | PDF/XLSX/XLS/CSV ≤ 50 MB, ≤ 500 pages; type and `asOfDate` (statements) required; returns job id; visible job status | DPS + SPA |
| FR-02 | Flow A extraction | Each proposal: metric, operator, threshold, unit, formula (Core DSL), frequency, page, quote, confidence, method | DPS |
| FR-03 | Citation check | Every published proposal's quote is found on its page and contains the threshold number; otherwise NEEDS_REVIEW | DPS |
| FR-04 | Mapping to library | Matched → `covenantMasterId`; unmatched → custom-covenant candidate; mapping shown in UI | DPS |
| FR-05 | Extraction review | Reviewer sees PDF page with highlight; can edit, approve or reject; every action audited | SPA + DPS |
| FR-06 | EXTRACTED rows in onboarding | Rows labelled EXTRACTED with source link; apply stores `thresholdSource = EXTRACTED` (or USER if edited); ThresholdAudit RECOMMENDED + APPLIED | Core + SPA |
| FR-07 | Flow B statement processing | Line items per period with source cell/page; derived inputs flagged `derived`; data-quality checks (balance, period, sign, required items) | DPS |
| FR-08 | Values API (C2) | Exactly Core's REST data-source contract; point-in-time and windowed; CP or obligor grain; units per measure | DPS |
| FR-09 | Re-assess on new data (C5) | After a statement is REPORTED, affected AUTO covenants are re-assessed within 5 minutes | Core |
| FR-10 | Decision rules | Core gate ladder unchanged: missing data ⇒ ERROR with diagnostic; never BREACH from missing data | Core |
| FR-11 | Manual covenants + evidence | PENDING_MANUAL; completion needs COMPLIANT/BREACHED/NA + note; evidence stored by `fileId` | Core |
| FR-12 | AI suggestions (C3) | Optional; confidence + rationale citing file/page; applying records `source = AI`; human always applies | DPS + Core |
| FR-13 | Waiver / extension with maker-checker | Per covenant, tied to latest assessment; extension date ≥ today; APPROVER role required when enabled | Core |
| FR-14 | Portfolio isolation | Every CP-scoped read/write (Core and DPS) returns 404/403 for CPs outside the user's RM/WRM portfolio; automated test per endpoint | Core + DPS |
| FR-15 | Advisory narrative | Shown separately, labelled "AI-generated, not a decision"; never changes status | DPS |
| FR-16 | End-to-end audit | From any assessment, navigate to the measure values → statement → page/cell; and to the applied covenant → extraction proposal → agreement page/quote | Both |
| FR-17 | Job operations | Support can view failed jobs, step errors, re-run or resume; audited | DPS + SPA |
| FR-18 | New unified UI | One React/TypeScript/Mantine app replacing both UIs, with URL routing and role-based menus. Screens: Portfolio (By-CP, agenda/calendar, analytics), CP workspace (covenants, assessments, documents, evidence, periods), Onboarding, Documents & extraction review, Library (covenants, measures, formula builder, data sources), Settings, Support, Document jobs & AI runs | UI |
| FR-19 | Maker-checker (configurable, default ON) | Waiver/extension created as REQUESTED by RM; APPROVER approves or rejects with note; only APPROVED counts as handled; setting to switch off; full audit (D4) | Core + UI |
| FR-20 | Manual entry of missing values | APPROVER can enter a missing line item for a CP + period with a mandatory note; stored as USER-sourced value in DPS; re-assessment triggered; shown with a "manual" badge everywhere (D9) | DPS + Core + UI |
| FR-21 | One agreement → several CPs | A document can be linked to N CPs (same obligor or listed facilities); proposals offered on each CP's onboarding; unlinking keeps history (D7) | DPS + UI |
| FR-22 | Certificates per CP and period | Certificate requirement per CP per reporting period with due date (from agreement frequency/due days or covenant frequency); states PENDING → SUBMITTED → ACCEPTED/REJECTED, OVERDUE after due date (D8) | Core + UI |
| FR-23 | Currency and units | Every value stored with its unit and currency; reporting-currency setting; dated FX table; UI shows source and converted values; thresholds compared in the measure's own unit (D6) | Core + DPS + UI |
| FR-24 | Period comparison and Excel | Compare any reporting periods for a CP side by side (values, results, headroom); export cycle matrix and master covenant lists to Excel (D3) | Core + UI |
| FR-25 | Portfolio analytics | Industry risk treemap, exposure by sector (reporting currency), entity mix, built from Core data and portfolio-scoped (D3) | Core + UI |
| FR-26 | Trend indicator | Per covenant: improving / stable / worsening from the last N assessments, shown as advisory (D3) | Core + UI |
| FR-27 | Ask AI (R3) | Answers only from the user's portfolio via Core APIs; cites the records used; context ≤ 10k chars; refuses outside scope (D3) | DPS + UI |
| FR-28 | Financial news (R3, optional) | Off by default; when enabled, shows headlines + advisory sentiment per CP; outbound access approved by bank (D3) | DPS + UI |
| FR-29 | Agreement terms beyond thresholds | Extract and show testing basis (quarterly / LTM), step-down schedules, cure/grace periods, defined terms (e.g. EBITDA definition) as review information; R3 makes them machine-usable (gap G2–G4) | DPS + UI |
| FR-30 | Facility dates | Effective, maturity and first test dates from the agreement or CP service; never invented (gap G5) | DPS + Core |
| FR-31 | Escalation | Configurable escalation (e.g. breach unhandled > N days → credit head) via notification service (gap G6) | Core |
| FR-32 | Management reports | Portfolio breach/compliance report by period (Excel + PDF), scoped by role (gap G7) | Core + UI |

## 7. Non-functional requirements

| Area | Requirement |
|---|---|
| Security | Entra ID SSO; JWT validated at gateway and in both services; roles RM/APPROVER/AUTHOR/SUPPORT/AUDITOR/SERVICE; no default secrets (startup fails); secrets in Key Vault; CSP; OWASP ASVS L2 target; penetration test before go-live |
| Privacy | Azure OpenAI via private endpoint; only needed text sent; no model training on bank data; prompt/output logs retained ≤ 90 days (policy to confirm) |
| Reliability | Core works when DPS is down (onboarding without EXTRACTED rows; assessments ERROR with `DATA_SOURCE_FAILURE`); DPS jobs retry transient errors; idempotent publish/report |
| Performance (targets, to measure) | 50-page text PDF Flow A p95 ≤ 90 s; statement Flow B p95 ≤ 60 s; C2 p95 ≤ 500 ms; daily run 10k covenants ≤ 30 min |
| Scalability | DPS workers scale on queue depth; Core 2+ instances with clustered Quartz |
| Data | SQL Server, one schema per service; migrations (Flyway / Alembic); daily backup, point-in-time restore; document retention per bank policy |
| Quality gates | Core: existing gates (95% line / 90% branch, SpotBugs, OWASP). DPS: pytest ≥ 90% line, golden-set gate (G6), bandit/semgrep, pip-audit. Contract tests for C1–C5 on both sides |
| Observability | Correlation id end to end; per-job step metrics; LLM tokens and cost per job; dashboards and alerts for failed jobs, ERROR rate, C2 latency |
| Accessibility | WCAG 2.1 AA for new screens (Mantine components) |

## 8. Dependencies
Entra ID app registrations; API gateway; SQL Server; file service in production; Azure OpenAI deployment (LLM steps only; **no cloud document-AI service**: parsing uses local pypdf, RapidOCR, pdfplumber and Docling); credit-proposal service; a golden set of ≥ 20 agreements and ≥ 20 statements labelled by credit analysts.

## 9. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Two teams, unclear ownership | Medium | High | Ownership table (02 §3); one product owner; OpenAPI contracts signed off in phase 0 |
| Wrong extracted threshold applied | Medium | High | Citation check, mandatory RM confirmation, provenance EXTRACTED, golden-set gate |
| Statement layouts vary | High | Medium | Start with bank templates; reviewer path; missing ⇒ ERROR not BREACH |
| Unit/currency mismatch | Medium | High | Units on every measure and value; conversion tests |
| Gateway identity not enforced | Medium | High | In-service JWT validation; FR-14 tests |
| AI services unproven (Core's are mocks) | High | Low | AI is optional; deterministic paths work without it |
| CrewAI version changes | Medium | Low | Pin version; flows use only `start/listen/router/persist`; contract tests |

## 10. Release plan

| Release | Scope | Est. duration |
|---|---|---|
| R0 Foundations | SSO/gateway, Core security fixes (identity, portfolio checks, default token, extension date), DPS skeleton, DB, job queue, contracts frozen, golden set started | 2–3 weeks |
| R1 MVP | FR-01…FR-10, FR-14, FR-16, FR-17, FR-18 (core screens), FR-19, FR-20, FR-21, FR-22, FR-23, FR-24, FR-15 | 8–10 weeks |
| R2 Evidence, AI & analytics | FR-11, FR-12, FR-13, FR-25, FR-26, FR-29 (display), FR-30, FR-31, FR-32; new masters/measures | 4–6 weeks |
| R3 Enhancements | FR-27, FR-28, FR-29 (machine-usable), amendments; Docling fallback tuning + LLM-assisted extraction; history import if needed | 4–8 weeks |

Durations are estimates, not measurements.

## 11. Decisions and open questions

Decisions D1–D12 are recorded in [08-open-decisions.md](08-open-decisions.md):
- new UI (React + TS + Mantine);
- uploads + bank systems;
- maker-checker configurable, default on;
- bank CP service for identity;
- unit per measure + reporting currency;
- manual values by approver;
- linked documents;
- certificates per CP and period;
- we build both codebases;
- deployment not decided (container-neutral build).

Still open:
1. Which IdP and gateway exist in the target environment (needed for FR-14/FR-19 roles)?
2. Deployment target (D10): Azure tenant or on-premises. Required before go-live.
3. Production owners/URLs for the file, credit-proposal, workflow and notification services.
4. Must our TinyDB history (185 records) be migrated?
5. Golden-set owner and labelling effort (D13).
6. Pilot scope: CPs, RMs, document types (D14).
