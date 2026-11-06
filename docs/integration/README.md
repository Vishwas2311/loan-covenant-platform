# Covenant Platform Integration: Document Set

**Purpose:** combine our **covenant automatic tracking** app (`covenanttrackingphase1.2`, Python) with the other team's **covenant-monitoring** app (Java Spring Boot + React) into one production product.

**Status:** draft v2, 2026-11-02, for manager and two-team review. It includes the "Processing & Orchestration" layer (CrewAI Flow A / Flow B / Background Workers) the manager asked for.

> These are the design documents written before the build (2 November 2026) and are kept as the record of what was decided. For the system as built, read [../architecture/covenant-platform.md](../architecture/covenant-platform.md), [../user-guide.md](../user-guide.md) and [../runbook.md](../runbook.md); decisions taken later are in [08-open-decisions.md](08-open-decisions.md) and `outputs/production-readiness/`.

## Read in this order

| # | Document | What it answers |
|---|---|---|
| 1 | [01-current-state.md](01-current-state.md) | What each app is and does today: architecture, strengths, verified problems |
| 2 | [02-target-architecture.md](02-target-architecture.md) | The combined architecture: layers, components, ownership, deployment, security |
| 3 | [03-flows-design.md](03-flows-design.md) | Flow A (Agreement Intake), Flow B (Periodic Assessment), Background Workers: steps, state, branches, errors |
| 4 | [04-api-contracts.md](04-api-contracts.md) | The exact APIs between the two services, plus the metric mapping table |
| 5 | [05-prd.md](05-prd.md) | Product requirements: goals, requirements, acceptance criteria, non-functional requirements, risks |
| 6 | [06-implementation-plan.md](06-implementation-plan.md) | Work items per team, phases, test plan, migration, rollout |
| 7 | [07-feature-inventory.md](07-feature-inventory.md) | Every screen, field and feature of both apps, with the merge decision for each |
| 8 | [08-open-decisions.md](08-open-decisions.md) | Decisions D1–D14 and what they change |
| 9 | [verification.md](verification.md) | Every check that was run and its result |

> **Naming:** "Document Processing Service (DPS)" in these docs means **our own Python service**. It is **not** Microsoft's "Azure AI Document Intelligence" product. Azure AI Document Intelligence is **not used** in this design (decision 2026-11-02; see [03 §8](03-flows-design.md)).

## The decision in one paragraph

Keep the other team's Spring Boot service as the **Covenant Core**: it is the system of record and the only place where compliance is decided. Turn our Python code into a **Document Processing Service (DPS)**. DPS runs two CrewAI Flows:
- **Flow A** reads loan agreements and sends covenant *proposals* (with page and quote) into Core's onboarding screen.
- **Flow B** reads periodic financial statements and serves the *numbers* to Core through Core's existing REST data-source contract.

Background workers run the flows off the web request. Core calculates, decides COMPLIANT / BREACHED / ERROR, schedules, notifies and audits.

This split was **tested live**: a stub of the DPS values API fed Core's engine and produced a correct BREACHED result with no change to Core's code (verification.md §1.3).

## Key facts behind the decision

| Fact | Evidence |
|---|---|
| Core is well tested | 964 unit + 40 integration tests pass; 97.2% line / 89.2% branch coverage; 0 SpotBugs findings |
| Core has no document reading; ours does | Code search; our extraction ran on 4 sample agreements in 1–6.5 s each |
| Our decision layer has confirmed defects that Core doesn't have | Whole-report waiver (9 stored reports wrong), any file = compliant (reproduced), overall COMPLIANT with gaps |
| Both apps have security gaps that must be fixed before production | Our XSS (reproduced); Core's endpoints trust the `X-User` header and some skip portfolio checks (reproduced); default support token |
| Our covenant catalog is a copy of Core's library | Same 42 covenant names, different metric keys (mapping in 04) |
| Document parsing stays local; no Azure AI Document Intelligence | pypdf + RapidOCR primary, pdfplumber tables, Docling fallback. Docling found the same numbers as pypdf on 7 sample agreements but was about 45–55× slower on CPU (verification.md §4) |

## Earlier documents
- [../application-review/](../application-review/overview.md): full review of our app (findings F-01…F-18 referenced here).
- [../application-review/covenant-monitoring-comparison.md](../application-review/covenant-monitoring-comparison.md): first comparison; superseded by this set.
- `architecture-and-prd.md`: v1 of this set; replaced by files 01–06.
