# covenant-platform — Covenexa (the product built on both apps)

| | |
|---|---|
| Built by | Our team, on top of [covenanttrackingphase1.2](covenanttrackingphase1.2.md) and [covenant-monitoring](covenant-monitoring.md) |
| Location | `C:\Users\vishw\covenant-platform`, GitHub `Vishwas2311/loan-covenant-platform` |
| Folders | `core/` (Java service), `dps/` (Python service), `ui/` (React app), `deploy/` (Docker Compose, Keycloak realm, stubs, mail bridge), `golden-set/` (extraction test set), `loadtest/`, `docs/` |
| In one line | covenant-monitoring became **Core**, the system of record that decides compliance; covenanttrackingphase1.2 became the **Document Processing Service (DPS)**, which reads agreements and statements; a new UI, real sign-in, borrower portal and production packaging were built around them |

Back to the [overview](README.md). What each page does for a user: [user-guide.md](../user-guide.md).

## 1. Technology stack, and where each piece came from

| Layer | Technology | Came from |
|---|---|---|
| UI | React 18, Mantine, TanStack Query, Vite build; served by **nginx**, which is also the gateway on port 8080 (`/api` → Core, `/dps` → DPS) | New |
| Sign-in | OpenID Connect: **Keycloak 26** locally, **Microsoft Entra ID** in production; JWT on every call; roles RM, APPROVER, AUTHOR, ADMIN, AUDITOR, SUPPORT, BORROWER | New |
| Core | Java 17, Spring Boot 3.5, Spring Security (JWT resource server), JPA, Quartz (clustered in production), formula engine, Flyway `V1`–`V32` | covenant-monitoring, extended |
| DPS | Python 3.12, FastAPI, **CrewAI Flows** (Flow A agreements, Flow B statements), SQLAlchemy + Alembic, pypdf, RapidOCR, **Docling** (heavy pool), openpyxl, xlrd | covenanttrackingphase1.2, rebuilt |
| AI | **Azure OpenAI**: Ask AI, agreement questions, narratives, AI suggestions; token use stored per call | Both, consolidated |
| Database | **SQL Server 2022** (Core's `covenant` database and DPS's tables), `READ_COMMITTED_SNAPSHOT` on | covenant-monitoring |
| Files | Document store volume for original PDFs and spreadsheets | New |
| Bank services | Credit-proposal, loan, notification, workflow, file and ML services; **WireMock** stand-ins locally | covenant-monitoring |
| Packaging | Docker images for Core, DPS (standard and heavy) and UI; Docker Compose; optional Mailpit and two-node files | New |
| CI | GitHub Actions: build and tests, coverage gates, SpotBugs, golden-set gate, bandit, semgrep, pip-audit, npm audit, Trivy image scan | New |

## 2. Deployment (local Docker stack)

```mermaid
%%{init: {"flowchart": {"wrappingWidth": 420}}}%%
flowchart TB
    browser(["Browser"])

    subgraph host["One machine — docker compose (deploy/)"]
        direction TB
        ui["ui — nginx :8080<br/>React app + gateway<br/>/api → core · /dps → dps-api"]
        kc["keycloak :8180<br/>realm 'covenant', test users<br/>(docker-compose.real.yml)"]
        core["core — Spring Boot :9310<br/>actuator :9320 (internal)"]
        dpsapi["dps-api — FastAPI :9400"]
        w1["dps-worker<br/>standard pool"]
        w2["dps-worker-heavy<br/>heavy pool (Docling)"]
        sql[("sqlserver :1433<br/>SQL Server 2022")]
        init["stack-init<br/>creates databases and logins"]
        stubs["bank-stubs — WireMock :9311<br/>CP, loan, notification, workflow, file, ML"]
        vol[("document store volume")]
        mp["mailpit :8025 + mail-bridge<br/>(docker-compose.mail.yml, optional)"]
    end

    aoai{{"Azure OpenAI<br/>(internet)"}}

    browser --> ui
    browser <--> kc
    ui --> core
    ui --> dpsapi
    core --> sql
    dpsapi --> sql
    w1 --> sql
    w2 --> sql
    dpsapi --> vol
    w1 --> vol
    w2 --> vol
    init --> sql
    core --> stubs
    core -. e-mails .-> mp
    core <--> dpsapi
    dpsapi --> aoai
    w1 --> aoai
    core -. verifies JWT .-> kc
    dpsapi -. verifies JWT .-> kc
```

In production the same images run on the bank's platform, Keycloak is replaced by Entra ID, WireMock by
the bank's real services, and Core can run as several nodes (`docker-compose.scale.yml` shows two).

## 3. Component architecture

```mermaid
%%{init: {"flowchart": {"wrappingWidth": 420}}}%%
flowchart TB
    staff(["RM · Approver · Author · Admin · Auditor · Support"])
    borrower(["Borrower"])
    idp{{"Keycloak / Entra ID<br/>OIDC, JWT, roles"}}

    subgraph uiL["UI — React + Mantine (new)"]
        spa["Portfolio · My tasks · CP workspace · Onboarding · Documents<br/>Reports + Ask AI · Covenant library · Measures · Data sources · Settings<br/>Document jobs · Support tools"]
        portal["Borrower portal"]
    end

    subgraph coreL["Core — Spring Boot (from covenant‑monitoring)"]
        sec["Security: JWT resource server, role checks,<br/>CP isolation (CpAccessGuard), support token"]
        kept["Kept from covenant-monitoring:<br/>library · measures · data sources · formula engine<br/>onboarding · assessment engine · staging + freshness gate<br/>waiver / extension · evidence · support toolkit"]
        added["Added in covenant-platform:<br/>maker-checker approvals · tasks + alerts · reports + exports<br/>certificates + reminders · borrower portal · letters<br/>loan-system sync · escalation digest · FX · stale-figures fallback<br/>portfolio cache · DPS clients (C1 recommendations, C2 values, C3 AI)"]
        q["Quartz (clustered)<br/>covenant checks · 05:30 fetch-ahead · 06:00 daily run · 5-min task sync"]
    end

    subgraph dpsL["DPS — FastAPI (from covenanttrackingphase1.2)"]
        dapi["API: uploads, documents, term review,<br/>values (C2), recommendations (C1), Ask AI, AI usage"]
        acc["Access check: asks Core whether this user may see this CP<br/>(answers cached 30 s; 503 when Core is busy)"]
        fa["Flow A — agreements<br/>parse → extract → normalise → validate<br/>→ publish / needs review / failed / heavy"]
        fb["Flow B — statements<br/>parse → extract and calculate → validate<br/>→ report / needs review / failed / heavy"]
        wk["Workers: standard + heavy pools,<br/>job queue with leases and 3 attempts"]
        ob["Event outbox → Core<br/>DATA_EVENT after new figures"]
    end

    sql[("SQL Server")]
    store[("Document store")]
    aoai{{"Azure OpenAI"}}
    bank{{"Bank services"}}

    staff --> spa
    borrower --> portal
    spa & portal <--> idp
    spa & portal -->|/api with JWT| sec
    spa -->|/dps with JWT| dapi
    sec --> kept & added
    q --> kept & added
    kept & added --> sql
    added -->|C1, C2, C3| dapi
    dapi --> acc -->|is this CP yours?| sec
    dapi --> wk --> fa & fb
    fa & fb --> store
    fb --> ob -->|DATA_EVENT| sec
    dapi & fa & fb --> sql
    dapi & added --> aoai
    added --> bank
```

## 4. Journey A — loan agreement to monitored covenants

```mermaid
sequenceDiagram
    autonumber
    actor RM
    participant UI
    participant DPS as DPS API
    participant W as DPS worker (Flow A)
    participant Core
    participant AI as Azure OpenAI

    RM->>UI: Documents → upload agreement (type Loan agreement, linked CPs)
    UI->>DPS: POST /dps/v1/documents (JWT)
    DPS->>Core: may this user write to these CPs?
    Core-->>DPS: yes
    DPS-->>UI: 202 documentId, jobId
    W->>W: parse (pypdf, scanned → heavy pool, Docling OCR)
    W->>W: extract covenants: metric, operator, threshold, page, quote
    W->>Core: covenant library (map each term to a library covenant)
    W->>W: validate: citation check, confidence, operator direction
    alt every term confident
        W->>W: PUBLISHED
    else needs a person
        W->>W: NEEDS_REVIEW
        RM->>UI: approve / edit / reject each term, Review done
    end
    RM->>UI: Onboarding → CP
    UI->>Core: GET onboarding recommendations
    Core->>DPS: C1 recommendations for the CP (amendments replace earlier terms)
    Core-->>UI: library matches + agreement terms + ML suggestions
    RM->>UI: tick covenants, Apply
    UI->>Core: POST applied covenants
    Core->>Core: create Quartz schedule per covenant
    opt questions
        RM->>UI: Ask about this agreement
        UI->>DPS: POST /dps/v1/documents/{id}/ask
        DPS->>AI: question + relevant clauses
    end
```

## 5. Journey B — statement figures to a covenant result

```mermaid
sequenceDiagram
    autonumber
    actor RM as RM or borrower
    participant UI
    participant DPS as DPS API
    participant W as DPS worker (Flow B)
    participant Core
    participant DB as SQL Server

    RM->>UI: upload statement (PDF, XLSX, XLS, CSV) + period end
    UI->>DPS: POST /dps/v1/documents
    W->>W: parse, read line items with page or cell reference
    W->>W: derive inputs (EBITDA, total debt...), data-quality checks
    alt checks pass
        W->>DB: dps_statement_value (REPORTED)
        W->>DB: dps_event_outbox DATA_EVENT
    else needs a person
        RM->>UI: corrections, Save
    end
    W->>Core: deliver DATA_EVENT (retries)
    Core->>Core: find covenants that use these figures
    Core->>DPS: C2 values for the CP and period (freshness gate)
    alt DPS answers
        Core->>DB: refresh cov_staging_value
    else DPS down, stored copy exists
        Core->>Core: use stored copy, note "Data may be stale"
    end
    Core->>Core: formula engine → COMPLIANT / BREACHED / ERROR
    Core->>DB: append cov_assessment, tasks and alerts follow
```

## 6. Sign-in and authorisation

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant UI as nginx + React
    participant IdP as Keycloak / Entra ID
    participant Core
    participant DPS

    U->>UI: open http://localhost:8080
    UI->>IdP: redirect to sign-in (OIDC, PKCE)
    U->>IdP: user name + password
    IdP-->>UI: access token (JWT: user, roles)
    UI->>Core: /api/... Bearer JWT
    Core->>Core: check signature, issuer, audience, role, CP owner check
    UI->>DPS: /dps/... Bearer JWT
    DPS->>DPS: check signature, issuer, audience
    DPS->>Core: access check for the CP (cached 30 s)
    Note over Core,DPS: Service to service: client-credentials tokens (SERVICE role)
```

## 7. What runs by itself

```mermaid
gantt
    title Daily timetable (India Standard Time)
    dateFormat HH:mm
    axisFormat %H:%M
    section Every 5 minutes
    Task sync (tasks and alerts for every CP) :t1, 00:00, 24h
    section Per covenant
    Own scheduled check, spread over the hour :c1, 02:00, 60m
    section Portfolio
    Fetch-ahead for covenants due in 3 days :p1, 05:30, 20m
    Daily run — loan sync, checks, RM summaries, reminders, reports, escalation :p2, 06:00, 30m
```

Also continuous: DPS workers read every upload; a new or corrected statement triggers re-checks within
seconds; the DPS outbox retries delivery to Core.

## 8. Data model added on top of covenant-monitoring

```mermaid
erDiagram
    dps_document ||--o{ dps_document_cp_link : "linked CPs"
    dps_document ||--o{ dps_job : "processing jobs"
    dps_document ||--o{ dps_extracted_term : "covenants read"
    dps_document ||--o{ dps_statement_value : "figures read"
    dps_document |o--o| dps_document : "amends"

    dps_document {
        string id PK
        string type "LOAN_AGREEMENT / FINANCIAL_STATEMENT / EVIDENCE"
        string status
        string amends_document_id FK
        string sha256
    }
    dps_extracted_term {
        string id PK
        string document_id FK
        string covenant_master_id
        string expression
        float confidence
        string status "PROPOSED / NEEDS_REVIEW / PUBLISHED / REJECTED / WITHDRAWN"
        int page
        string quote
    }
    dps_statement_value {
        string id PK
        string document_id FK
        string cp_id
        string key
        date as_of_date
        float value
        bool superseded
    }
```

New Core tables, added to covenant-monitoring's 23:

```mermaid
erDiagram
    cov_applied_covenant ||--o{ cov_work_task : "tasks"
    cov_applied_covenant ||--o{ cov_letter_log : "letters"
    cov_credit_proposal_cache ||--o{ cov_certificate_requirement : "certificates due"
    cov_credit_proposal_cache ||--o| cov_borrower_contact : "reminders to"
    cov_borrower_contact ||--o{ cov_reminder_log : "sent"
    cov_credit_proposal_cache ||--o{ cov_portal_access : "borrower users"
    cov_portal_access ||--o{ cov_portal_submission : "certificates, statements"
    cov_loan_sync_run ||--o{ cov_loan_sync_change : "changes"
```

New Core tables: `cov_work_task`, `cov_saved_report`, `cov_certificate_requirement`,
`cov_borrower_contact`, `cov_reminder_log`, `cov_portal_access`, `cov_portal_submission`,
`cov_letter_log`, `cov_loan_sync_run`, `cov_loan_sync_change`, `cov_data_event`, `cov_fx_rate`,
`cov_manual_run` (36 `cov_*` tables in all, up from 23). DPS tables: `dps_document`,
`dps_document_cp_link`, `dps_job`, `dps_extracted_term`, `dps_statement_value`, `dps_event_outbox`,
`dps_audit_event`, `dps_ai_usage`, `dps_flow_state`.

## 9. Build and quality pipeline

```mermaid
%%{init: {"flowchart": {"wrappingWidth": 420}}}%%
flowchart LR
    push["git push"] --> ci & secw
    subgraph ci["CI workflow"]
        b1["Core: Maven verify<br/>unit + integration, JaCoCo, SpotBugs"]
        b2["DPS: ruff, pytest 90%+ coverage"]
        b3["Golden set: tests + synthetic gate<br/>recall, precision, thresholds"]
        b5["DPS on SQL Server: migrations,<br/>no drift, tests"]
        b4["UI: Vitest coverage, type-check, build"]
        b6["Docker images: compose check, builds"]
    end
    subgraph secw["Security workflow"]
        s1["semgrep"]
        s2["bandit (DPS)"]
        s3["pip-audit (DPS)"]
        s4["npm audit (UI)"]
        s5["Trivy: Core, DPS, UI images"]
    end
```

## 10. What came from where

| Capability | From covenanttrackingphase1.2 | From covenant-monitoring | New in covenant-platform |
|---|---|---|---|
| Reading loan agreements | Regex extraction, OCR | — | Flow A, citation check, review screen, amendments, security covenants |
| Reading statements | Excel/PDF reading, ratio formulas, metric aliases | — | Flow B, cell references, data-quality checks, `.xls`, corrections, Values API (C2) |
| Covenant rules | Master covenant list, custom formulas | Library, eligibility, formula language, measures, data sources | Agreement terms feed onboarding |
| Checking covenants | One run per upload | Quartz, assessment engine, staging | Spread schedules, clustered scheduler, re-check on new data, stale-figures fallback |
| Breach workflow | Human review of breaches | Waiver / extension, evidence, AI suggestions | Maker-checker, tasks, alerts, letters, escalation digest |
| AI | 7 CrewAI agents on gpt-4.1 | AI status-suggestion client | Azure OpenAI, Ask AI limited to the user's CPs, agreement Q&A, token tracking |
| Users and security | None | `X-User` header | OIDC sign-in, 7 roles, CP isolation in Core and DPS, support token |
| Borrowers | — | — | Borrower portal, reminders, certificate tracking |
| Storage | JSON files | H2 / SQL Server | SQL Server for both services, document store |
| Operations | Run from a terminal | Support toolkit | Docker images, runbook, CI security gates, load-tested on one and two Core nodes |
