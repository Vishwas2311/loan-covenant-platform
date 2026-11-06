# Architecture of the three covenant systems

Covenexa (`covenant-platform`) was built on top of two earlier applications. Each has its own page
with its stack, component diagram, main journeys, data model and API map.

| # | System | Built by | Page | Where the code lives |
|---|---|---|---|---|
| 1 | **covenanttrackingphase1.2** — "Covenant automatic tracking" | Our team | [covenanttrackingphase1.2.md](covenanttrackingphase1.2.md) | Outside this repository (the development laptop) |
| 2 | **covenant-monitoring** — "Covenant Monitoring System" | The second team | [covenant-monitoring.md](covenant-monitoring.md) | Outside this repository (the development laptop) |
| 3 | **covenant-platform** — Covenexa, the combined product | Built on 1 + 2 | [covenant-platform.md](covenant-platform.md) | This repository (GitHub `Vishwas2311/loan-covenant-platform`) |

The complete Covenexa architecture in a single diagram: [covenant-platform.md, section 0](covenant-platform.md#0-the-whole-system-in-one-diagram).

The same diagram as an image, sharp when zoomed: [Covenexa-full-architecture.png](Covenexa-full-architecture.png). Every feature of the three apps side by side, with a search box and filters (open the file in a browser): [three-app-feature-comparison.html](three-app-feature-comparison.html).

## How the three relate

```mermaid
%%{init: {"flowchart": {"wrappingWidth": 420}}}%%
flowchart TB
    subgraph ours["1 · covenanttrackingphase1.2 — our team, Python"]
        direction LR
        o1["Loan-agreement reading<br/>regex + OCR"]
        o2["Statement reading<br/>Excel / PDF, ratio formulas"]
        o3["CrewAI agents<br/>reports, chat, news"]
        o4["FastAPI + one-page UI<br/>JSON files"]
    end

    subgraph theirs["2 · covenant‑monitoring — second team, Java"]
        direction LR
        t1["Covenant library<br/>+ formula language"]
        t2["Measures, data sources<br/>staging + freshness gate"]
        t3["Quartz scheduling<br/>+ assessment engine"]
        t4["Waivers, evidence, RM e-mails<br/>support toolkit"]
        t5["React + Mantine UI<br/>X-User header"]
    end

    subgraph platform["3 · covenant‑platform — Covenexa"]
        direction LR
        ui["New UI<br/>+ nginx gateway"]
        dps["DPS — Document Processing Service<br/>FastAPI + CrewAI Flows"]
        core["Core<br/>Spring Boot"]
        dps <-->|"C1 · C2 · C3 · DATA_EVENT"| core
    end

    new["New in covenant-platform: OIDC sign-in and 7 roles · maker-checker · tasks and alerts<br/>borrower portal · reminders · reports · Azure OpenAI · SQL Server for both · Docker · CI security gates"]

    o1 -->|"Flow A"| dps
    o2 -->|"Flow B"| dps
    o3 -->|"Azure OpenAI"| dps
    o4 -.->|replaced| ui
    t1 & t2 & t3 & t4 --> core
    t5 -.->|replaced| ui
    platform --- new
```

## Side by side

| | covenanttrackingphase1.2 | covenant-monitoring | covenant-platform |
|---|---|---|---|
| Purpose | Read one company's documents and report its covenant ratios | Monitor covenants for every CP from the bank's data | Both, end to end, for the whole bank |
| Language | Python 3.12 | Java 17 + TypeScript | Java 17 + Python 3.12 + TypeScript |
| Backend | FastAPI, one process | Spring Boot 3.5 | Core (Spring Boot 3.5) + DPS (FastAPI) + DPS workers |
| UI | HTML inside `web.py` | React 18 + Mantine 8 | React 18 + Mantine, behind nginx |
| AI | CrewAI agents, gpt-4.1 | AI suggestion client | CrewAI Flows, Azure OpenAI |
| Documents | Regex + OCR | None | Flow A / Flow B, Docling for scans, human review |
| Data store | JSON files (TinyDB) | H2 / SQL Server | SQL Server + document store |
| Scheduling | None | Quartz, single node | Quartz, clustered |
| Sign-in | None | `X-User` header | Keycloak / Entra ID, 7 roles |
| E-mail | None | RM summary e-mail | Summaries, escalation digest, borrower reminders, saved reports |
| Packaging | Run from a terminal | `start.bat` | Docker images, Compose, runbook |
| Quality gates | None | Coverage, SpotBugs, OWASP | Coverage, SpotBugs, golden set, bandit, semgrep, pip-audit, npm audit, Trivy |

More detail: [`docs/integration/01-current-state.md`](../integration/01-current-state.md) (both original
apps as found on 2 November), [`docs/integration/02-target-architecture.md`](../integration/02-target-architecture.md)
(the design), and [`docs/user-guide.md`](../user-guide.md) (every feature).
