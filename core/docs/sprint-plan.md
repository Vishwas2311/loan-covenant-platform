# Covenant Monitoring System — Sprint Plan

## Assumptions

| Item | Value |
|---|---|
| Team | 2 Backend Engineers + 2 Frontend Engineers + 1 QA Engineer + 1 Tech Lead (50% IC, 50% leadership) |
| Sprint length | 2 weeks |
| Working days / sprint | 10 (minus 1 day for ceremonies = ~9 productive days) |
| Pace | Quality-first — no hard deadline; built-in buffer for refactor, tech-debt, UAT |
| Velocity target | Sprint 1–2: ~22 pts (setup overhead) · Sprint 3–7: ~38 pts (steady state) · Sprint 8+: ~30 pts (hardening / lower throughput) |
| Story points | Fibonacci: 1, 2, 3, 5, 8, 13 (anything >8 must be split) |
| Definition of Done | Code merged · unit tests ≥80% coverage on new code · integration test (where applicable) · QA-signed · Storybook entry for new shared FE components · Swagger updated · merged to `main` behind a feature flag where it touches user flow |

## Story Point Estimation (40 stories, ~196 pts total)

| Story | Pts | Notes |
|---|---|---|
| US-001 Create covenant | 5 | API + UI + uniqueness constraint |
| US-002 Edit covenant | 5 | + change log + impact warning |
| US-003 Deactivate covenant | 3 | Soft-delete + filter |
| US-004 Search & filter library | 5 | Server-side pagination, sort, multi-filter |
| US-005 View covenant detail + usage | 3 | Usage query + drill-down |
| US-006 SpEL save-time validation | 5 | Parser + reference check |
| US-007 Interactive expression validation | 3 | Endpoint + UI button |
| US-008 Qualitative covenants | 2 | Mostly tests + sample data |
| US-009 SpEL injection guard | 5 | Denylist + SimpleEvaluationContext + security tests |
| US-010 CP lookup wizard step | 3 | External client + step 1 UI |
| US-011 Fetch CP attributes (caching) | 3 | Client + cache table |
| US-012 Recommendation engine | 5 | Service + endpoint + suggestion UI |
| US-013 Override recommendation | 2 | Local state + status update |
| US-014 Add ad-hoc covenants | 3 | Picker modal |
| US-015 Frequency picker (standard) | 3 | Enum → cron map + component |
| US-016 Custom cron | 3 | Validator + UI escape hatch |
| US-017 Update frequency on active | 5 | Patch + Quartz reschedule + audit |
| US-018 Suspend/resume | 3 | Patch + pause/resume |
| US-019 Create monitoring schedule | 5 | Quartz integration |
| US-020 Schedule survives restart | 3 | JDBC store config + integration test |
| US-021 Run scheduled assessment | 5 | Quartz Job + service |
| US-022 Evaluate rule | 5 | Core evaluator |
| US-023 Handle missing data | 3 | Short-circuit + tests |
| US-024 Capture data snapshot | 2 | JSON column + immutability |
| US-025 Literal substitution audit | 3 | Renderer utility |
| US-026 Manual trigger | 3 | Endpoint + UI button |
| US-027 Workflow kickoff | 5 | WebClient + timeout + error column |
| US-028 Link to workflow instance | 1 | UI link |
| US-029 Dashboard summary | 5 | Aggregation query + dashboard |
| US-030 CP drill-down | 5 | Detail page + actions |
| US-031 Assessment history | 3 | History endpoint + table |
| US-032 Filter by result status | 2 | Query param + chip filter |
| US-033 WireMock stubs | 5 | Mappings for 4 services + 3 sample CPs |
| US-034 Configurable base URLs | 2 | @ConfigurationProperties |
| US-035 Audit trail of covenant edits | 5 | Envers or custom listener |
| US-036 Persistent assessment history | 2 | Service-layer immutability |
| US-037 Identify manual trigger user | 2 | Column + auth principal capture |
| US-038 Consistent error envelope | 3 | @ControllerAdvice |
| US-039 H2 in MSSQL mode | 3 | Config + Flyway baseline |
| US-040 Swagger UI | 2 | springdoc config |
| **Total** | **~135 pts story work** | + ~60 pts spike/setup/hardening (Sprint 0, Sprint 8–9) |

---

## Sprint 0 — Inception (1 week, before Sprint 1)

**Goal**: Team aligned, environments ready, story refinement complete.

| Activity | Owner |
|---|---|
| Story estimation poker — confirm point assignments | Team |
| Repo creation (`covenant-backend`, `covenant-frontend`), branch protection | Tech Lead |
| CI scaffold: GitHub Actions (or Jenkins) for `mvn verify`, `npm ci && npm test`, Docker image build | BE-1 + Tech Lead |
| Dev environments — JDK 17, Node 20, Docker, IntelliJ/VSCode templates | Team |
| Domain modeling workshop — entity relationships, identifier conventions | Tech Lead + PO |
| Wireframe walkthrough (figma) for 5 main UIs | FE-1 + PO |
| Confluence/Jira board with epics + US-001..040 imported | Tech Lead |
| OpenAPI contract draft for external clients (CP, Attributes, Data, Workflow) | BE-2 |

**Exit criteria**: Backlog refined to "ready for sprint planning", CI pipelines green on hello-world commit, wireframes signed off.

---

## Sprint 1 — Foundation
**Dates**: Weeks 1–2 · **Target velocity**: 22 pts · **Milestone**: *Skeleton walking — covenant CRUD API live*

**Sprint Goal**: A booting Spring Boot service with covenant CRUD API, schema-managed by Flyway, and a Vite/Mantine shell that lists covenants from the API.

| ID | Story | Pts | Owner |
|---|---|---|---|
| US-039 | H2 in MSSQL mode | 3 | BE-1 |
| US-038 | Consistent error envelope | 3 | BE-2 |
| US-040 | Swagger UI | 2 | BE-1 |
| US-001 | Create covenant (API only) | 3 | BE-2 |
| US-002 | Edit covenant (API only, no change-log yet) | 3 | BE-2 |
| US-003 | Deactivate covenant (API only) | 2 | BE-1 |
| — | FE shell: Vite + Mantine v8 + theme + router + TanStack Query setup | 5 | FE-1 + FE-2 |
| — | Shared `fetch` client wrapper + error toast pattern | 2 | FE-1 |

**Risks**
- H2 MSSQL mode subtleties around UUID / `IDENTITY` columns — owned by BE-1; spike if needed.
- TanStack Query v5 + Mantine v8 SSR/CSR interaction — owned by FE-1.

**Demo**: cURL the API, show Swagger; show empty Mantine app shell.

---

## Sprint 2 — Library UI + Rule Authoring
**Dates**: Weeks 3–4 · **Target velocity**: 30 pts · **Milestone**: *Library is usable end-to-end by an admin*

**Sprint Goal**: Admin can create, edit, deactivate, search, and validate covenant rules through the UI. SpEL injection guarded.

| ID | Story | Pts | Owner |
|---|---|---|---|
| US-001 | Create covenant (UI completion) | 2 | FE-1 |
| US-002 | Edit covenant (UI + change log table) | 2 | FE-1 + BE-2 |
| US-003 | Deactivate covenant (UI) | 1 | FE-2 |
| US-004 | Search & filter library | 5 | FE-2 + BE-1 |
| US-005 | View detail + usage | 3 | FE-1 + BE-1 |
| US-006 | SpEL save-time validation | 5 | BE-2 |
| US-007 | Interactive expression validation | 3 | BE-2 + FE-1 |
| US-009 | SpEL injection guard | 5 | BE-2 (Tech Lead reviews) |
| US-008 | Qualitative covenants (seed data + tests) | 2 | BE-1 |

**Spike (timeboxed 1 day)**: Decision on JPA Envers vs custom audit listener for US-035.

**Demo**: Walk through creating a covenant, validating bad/good expressions, deactivating one, finding it via filter.

---

## Sprint 3 — External Integrations + Recommendation Engine
**Dates**: Weeks 5–6 · **Target velocity**: 36 pts · **Milestone**: *Recommendation list renders for a CP*

**Sprint Goal**: External APIs stubbed via WireMock; CP onboarding wizard step 1 + step 2 working end-to-end.

| ID | Story | Pts | Owner |
|---|---|---|---|
| US-033 | WireMock stubs (CP, Attributes, Data, Workflow) | 5 | BE-1 |
| US-034 | Configurable base URLs | 2 | BE-1 |
| US-010 | CP lookup wizard step | 3 | FE-1 + BE-2 |
| US-011 | Fetch CP attributes (caching) | 3 | BE-2 |
| US-012 | Recommendation engine | 5 | BE-2 + FE-2 |
| — | Wizard Step 1 + Step 2 UI (Mantine Stepper, integration with above) | 5 | FE-1 + FE-2 |
| — | Integration tests using WireMock for all 4 clients | 3 | BE-1 + QA |
| — | Tech debt buffer | 5 | Team |

**Demo**: Enter `CP-1001`, see CP details, see 4-5 suggested covenants with match reasons.

---

## Sprint 4 — Onboarding Wizard Complete
**Dates**: Weeks 7–8 · **Target velocity**: 38 pts · **Milestone**: *Onboarding wizard is feature-complete end-to-end (without scheduling yet)*

**Sprint Goal**: Operator can complete the wizard: lookup → review/override → pick frequency → confirm. Applied covenants persisted with snapshot data.

| ID | Story | Pts | Owner |
|---|---|---|---|
| US-013 | Override recommendation (uncheck) | 2 | FE-1 |
| US-014 | Add ad-hoc from library | 3 | FE-2 |
| US-015 | Frequency picker (standard) | 3 | FE-1 + BE-2 |
| US-016 | Custom cron | 3 | FE-2 + BE-1 |
| — | `AppliedCovenant` persistence + snapshot capture (API) | 5 | BE-2 |
| — | Wizard Step 3 (frequency) + Step 4 (confirm) UI | 5 | FE-1 |
| — | Wizard end-to-end happy-path E2E test (Playwright) | 3 | QA |
| — | Wizard error / retry states | 3 | FE-2 |
| — | Backend transactional consistency tests for the bulk apply call | 3 | BE-1 |
| — | Tech debt + minor polish | 3 | Team |

**Demo**: Full wizard run for 2 CPs of different segments, persistence verified in DB.

---

## Sprint 5 — Scheduling + Assessment Execution
**Dates**: Weeks 9–10 · **Target velocity**: 38 pts · **Milestone**: *Assessments run on cron and persist results*

**Sprint Goal**: Quartz schedules created during apply; assessments fire on schedule; evaluator works against Data API; persists `Assessment` rows with result, snapshot, resolved expression.

| ID | Story | Pts | Owner |
|---|---|---|---|
| US-019 | Create monitoring schedule | 5 | BE-1 |
| US-020 | Schedule survives restart | 3 | BE-1 + QA |
| US-021 | Run scheduled assessment | 5 | BE-2 |
| US-022 | Evaluate rule | 5 | BE-2 |
| US-023 | Handle missing data | 3 | BE-2 |
| US-024 | Capture data snapshot | 2 | BE-2 |
| US-025 | Literal substitution audit | 3 | BE-1 |
| — | `AssessmentJob` Quartz wiring + transactional service | 5 | BE-2 |
| — | Frontend: read-only "Applied covenants" list per CP (no dashboard yet) | 3 | FE-1 |
| — | Integration test: end-to-end cron tick → assessment persisted | 3 | QA + BE-1 |

**Risk**: Quartz misfire policy behaviour under H2 — covered by US-020. If problems, BE-1 spikes early in sprint.

**Demo**: Set a covenant to fire every minute (test cron), watch `Assessment` rows accrete in H2 console.

---

## Sprint 6 — Workflow Integration + Audit
**Dates**: Weeks 11–12 · **Target velocity**: 38 pts · **Milestone**: *Each assessment kicks off a workflow; full audit trail in place*

**Sprint Goal**: Workflow service called per assessment; assessment detail page reads end-to-end; covenant edit audit trail recorded.

| ID | Story | Pts | Owner |
|---|---|---|---|
| US-027 | Workflow kickoff | 5 | BE-1 |
| US-035 | Audit trail of covenant edits | 5 | BE-2 |
| US-036 | Persistent assessment history (immutability) | 2 | BE-2 |
| US-017 | Update frequency on active | 5 | BE-1 + FE-2 |
| — | Assessment Detail page (FE) — snapshot table, resolved expression, result badge | 5 | FE-1 |
| US-028 | Link to workflow instance | 1 | FE-1 |
| US-037 | Identify manual trigger user | 2 | BE-2 + FE-2 |
| US-026 | Manual trigger | 3 | BE-1 + FE-2 |
| — | Performance smoke: 1000 covenants × 100 CPs schedule-creation timing | 3 | BE-1 + QA |
| — | Tech debt + bug fixes | 3 | Team |

**Demo**: Trigger manual assessment, see workflow link work; edit a covenant rule, see change log entry in audit endpoint.

---

## Sprint 7 — Monitoring Dashboard
**Dates**: Weeks 13–14 · **Target velocity**: 38 pts · **Milestone**: *Risk users have visibility — MVP feature-complete*

**Sprint Goal**: Risk assessors can see all CPs, drill into a CP, view history, filter by status. **End of P0 scope.**

| ID | Story | Pts | Owner |
|---|---|---|---|
| US-029 | Dashboard summary | 5 | BE-2 + FE-1 |
| US-030 | CP drill-down | 5 | BE-2 + FE-1 |
| US-031 | Assessment history | 3 | BE-1 + FE-2 |
| US-032 | Filter by result status | 2 | FE-2 |
| US-018 | Suspend/resume | 3 | BE-1 + FE-2 |
| — | Accessibility audit + WCAG AA fixes (Mantine baseline + custom) | 5 | FE-1 + QA |
| — | Loading skeletons, empty states across dashboard | 3 | FE-2 |
| — | Backend pagination/index tuning for monitoring queries | 5 | BE-1 |
| — | E2E test: full lifecycle — onboard → schedule → fires → dashboard | 5 | QA |
| — | Documentation sweep: README, runbook, API guide | 3 | Tech Lead |

**Milestone gate**: **MVP feature-complete**. Demo to PO + stakeholders.

---

## Sprint 8 — Hardening / Stabilisation
**Dates**: Weeks 15–16 · **Target velocity**: 30 pts (intentional drop) · **Milestone**: *Release candidate*

**Sprint Goal**: Bug bash, performance, security review, documentation. No new feature scope unless gap-found.

| Workstream | Items |
|---|---|
| Bug bash | Allocate first 2 days of sprint; team-wide hunt across all features |
| Security review | Threat model walkthrough; OWASP top-10 review of API surface; review SpEL guards |
| Performance | Load test: 10K covenants in library, 1K CPs with 10 covenants each, simulated daily ticks — measure p95 query times, Quartz throughput, DB connection pool tuning |
| Observability | Add structured logs (MDC with `cpId`, `assessmentId`), Micrometer metrics for Quartz triggers, basic Grafana dashboard spec |
| UAT prep | Deploy to UAT env (real MSSQL); load realistic seed data; train pilot users (2 admins, 4 ops officers, 2 assessors) |
| Pilot UAT | Pilot users exercise the system for ~5 days; defects logged and triaged |
| P1 cleanup | Pull in any P1 stories not yet done (re-check US-005, US-018, US-026, US-028, US-032, US-040) |

**Exit criteria**: Zero P0/P1 defects open, p95 query times within budget (target: dashboard summary <500ms for 1K CPs), security review sign-off.

---

## Sprint 9 — UAT Fixes + Release
**Dates**: Weeks 17–18 · **Target velocity**: 25 pts · **Milestone**: *GA / Production release*

**Sprint Goal**: Fix UAT defects, finalise documentation, deploy to production with controlled rollout.

| Workstream | Items |
|---|---|
| UAT defect fixes | Sprint capacity: ~15 pts reserved for defect work |
| Production readiness | Final ops handover: runbook, oncall doc, alert thresholds, dashboards |
| Data migration plan | Document/script for first prod load: seeding the master library from existing policy docs |
| Release | Blue-green deploy to prod (off-peak window); smoke tests; gradual ramp (5% → 25% → 100% of CPs onboarded over a week) |
| Release notes | Drafted by PO + Tech Lead, distributed to stakeholders |
| Post-release | Week 2 of sprint: monitor metrics, on-call rota, daily standup focused on prod health |

**Exit criteria**: System in production handling real CPs, no Sev-1/2 incidents in first week post-launch.

---

## Backlog (P2 / post-GA)

Captured for the next quarter's roadmap. Not in Sprint 1–9.

- Bulk CSV import for covenant master.
- Native breach notifications (email / Slack) — currently delegated to workflow service.
- Multi-tenancy (tenant scoping of library + monitoring).
- Historical re-evaluation / backfill of assessments after rule edits.
- Audit-trail UI surface (data exists; UI deferred).
- Role-based UI (admin / ops / assessor distinct experiences).
- SSO integration replacing gateway-header identity assumption.
- Cron timezone per-CP override (currently UTC system-wide).
- Workflow retry job for failed kickoffs.
- Archival / cold-storage strategy for assessments older than N years.

---

## Critical Path & Dependencies

```
Sprint 1 → 2: Library CRUD is prerequisite for everything (covenants must exist before they can be recommended)
Sprint 3 (US-033 WireMock) → blocks all downstream integration work, must land week 1 of sprint 3
Sprint 4 AppliedCovenant persistence → blocks Sprint 5 scheduling
Sprint 5 schedule + assessment → blocks Sprint 6 workflow + audit
Sprint 6 audit + immutability → blocks Sprint 8 security sign-off
```

**Highest-risk items (early spikes recommended)**:
1. **SpEL injection guard (US-009)** — Sprint 2, owned by BE-2, Tech Lead reviews.
2. **Quartz durability on H2 / MSSQL (US-020)** — Sprint 5, owned by BE-1, integration test in same sprint.
3. **Workflow service contract drift** — Sprint 6, owned by BE-1; mitigated by WireMock matching the published contract from external team.
4. **MSSQL behavioural divergence from H2** — surfaced in Sprint 8 UAT against real MSSQL; reserve buffer for migration tweaks.

---

## Sprint Ceremonies

| Ceremony | Cadence | Duration | Attendees |
|---|---|---|---|
| Sprint planning | Day 1 of sprint | 2 hours | Whole team + PO |
| Daily standup | Daily | 15 min | Whole team |
| Backlog refinement | Mid-sprint (day 5) | 1 hour | Whole team + PO |
| Sprint review / demo | Last day | 1 hour | Team + PO + stakeholders |
| Sprint retro | After review | 1 hour | Whole team |

---

## Velocity & Burndown Tracking

| Sprint | Planned | Stretch | Goal |
|---|---|---|---|
| 1 | 22 | 25 | Foundation |
| 2 | 28 | 32 | Library + Rule Authoring |
| 3 | 33 | 38 | Externals + Recommendation |
| 4 | 35 | 40 | Wizard Complete |
| 5 | 36 | 40 | Scheduling + Assessment |
| 6 | 34 | 38 | Workflow + Audit |
| 7 | 36 | 40 | Dashboard (MVP gate) |
| 8 | 28 | 32 | Hardening |
| 9 | 22 | 28 | UAT + Release |

**Total**: ~274 pts planned over 18 weeks. ~135 pts of story work + ~140 pts of supporting work (CI, testing, spikes, hardening, UAT). The ratio is intentional: roughly half the calendar effort goes into making the features production-real.

---

## Communication & Reporting

- **Daily**: Standup updates the team-internal board.
- **Weekly**: Tech Lead posts a written status to stakeholders (Slack `#covenant-build`): completed, in-flight, blockers, risk register changes.
- **End of sprint**: Demo recording (Loom) + sprint notes shared.
- **Milestone gates**: PO sign-off after Sprint 2, 4, 6, 7, 9 — formal go/no-go for next phase.

---

## Risk Register (snapshot — to be maintained live)

| # | Risk | Impact | Likelihood | Mitigation |
|---|---|---|---|---|
| R1 | External CP / Data / Workflow APIs aren't ready or contract changes | High | Medium | WireMock from sprint 3; PO syncs weekly with external teams; contract test job in CI verifying our clients against published OpenAPI |
| R2 | SpEL security gap | High | Low | Two layers of defence (denylist + SimpleEvaluationContext); dedicated security tests; Tech Lead reviews all SpEL code |
| R3 | Quartz behaves differently on real MSSQL vs H2 | Medium | Medium | Sprint 8 UAT runs against MSSQL; reserve 5 pts buffer in Sprint 9 for MSSQL-specific fixes |
| R4 | UI scope creep (e.g. stakeholders request charts, exports mid-sprint) | Medium | High | Strict scope discipline; new requests go to backlog; PO/Tech Lead gate-keep |
| R5 | Two-FE coordination on Mantine v8 patterns | Low | Medium | Pair on shared components in Sprint 1; Storybook from Sprint 2 onwards |
| R6 | Performance regression on monitoring dashboard at scale | Medium | Medium | Sprint 7 includes pagination/index tuning; Sprint 8 load test |
