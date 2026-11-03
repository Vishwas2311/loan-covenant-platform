# 02: Target Architecture (Combined Product)

## 1. Principles
1. **One brain for decisions.** Only Covenant Core calculates covenant results and decides COMPLIANT / BREACHED / ERROR, waivers and extensions. Nothing else may write a compliance status.
2. **Documents become facts, not verdicts.** The Document Processing Service (DPS) turns documents into *proposed covenants* and *financial values*, each with its source page or cell and a confidence score.
3. **Humans approve.** Extracted covenants never become active without RM confirmation. Low-confidence data goes to review, not to Core.
4. **Reuse existing contracts.** DPS plugs into Core through interfaces Core already has: REST data source, recommender pattern, AI recommender and file service. Core changes stay small.
5. **Never block the user.** Long work (PDF parsing, OCR, LLM) runs in background workers. Core keeps working if DPS is down.
6. **Secure by default.** SSO at the gateway **and** checks in each service; portfolio isolation on every CP endpoint; no default secrets.

## 2. Layered architecture

The layers follow the manager's numbering ("3 = Processing & Orchestration").

```mermaid
flowchart TB
  subgraph L1["1 · Experience"]
    SPA["Covenant Platform UI — NEW React 18 + TypeScript + Mantine SPA<br/>(reuses their tested components)<br/>Portfolio · CPs · Onboarding · Documents · Review · Library · Settings · Support"]
  end
  subgraph L2["2 · Access & Security"]
    GW[API Gateway<br/>Entra ID SSO · JWT validation · roles · rate limits<br/>strips client X-User, sets verified identity]
  end
  subgraph L3["3 · Processing & Orchestration — Document Processing Service (Python, ours)"]
    DIAPI[DPS REST API /dps/v1/**<br/>FastAPI]
    Q[[Job queue<br/>dps_job table → Service Bus later]]
    subgraph W["Background Workers"]
      FA["CrewAI Flow A — Agreement Intake<br/>Parse → Extract → Normalize → Validate → Publish"]
      FB["CrewAI Flow B — Periodic Assessment (data side)<br/>Parse → Extract → Calculate* → Validate → Report"]
    end
    subgraph PE["ParserEngine — local, open source (no Azure AI Document Intelligence)"]
      P1["Primary: pypdf text + RapidOCR<br/>(OCR only pages without text)"]
      P2["Tables: pdfplumber"]
      P3["Fallback: Docling<br/>(hard scans / complex tables, separate heavy worker pool)"]
    end
    VAL["Values API — serves statement data<br/>to Core (REST data-source contract)"]
    AIS["AI status suggestions<br/>(Core's AiStatusRecommender contract)"]
  end
  subgraph L4["4 · Covenant Core — Spring Boot (theirs)"]
    CORE[REST /api/v1/**]
    ONB[Onboarding: master + ML + EXTRACTED recommendations]
    ENG[Formula engine + Assessment gate ladder]
    SCH[Quartz scheduler · 06:00 breach run]
    MON["Monitoring · Review · Disposition (+ maker-checker)"]
    AUD[Audit tables]
  end
  subgraph L5["5 · Data"]
    CSQL[(SQL Server — core schema<br/>cov_* · QRTZ_*)]
    DSQL[(SQL Server — dps schema<br/>dps_document · dps_job · dps_flow_state ·<br/>dps_extracted_term · dps_statement_value)]
    FILES[(File service / Blob storage)]
  end
  subgraph L6["6 · External & AI services"]
    AOAI[(Azure OpenAI — private endpoint)]
    BANK[(Bank REST / MSSQL data sources)]
    CPS[(Credit-proposal service)]
    WF[(Workflow engine)]
    NTF[(Notification service)]
  end

  SPA --> GW
  GW --> CORE
  GW --> DIAPI
  DIAPI --> Q --> FA & FB
  FA & FB -->|Parse step| P1
  FB -->|statement tables| P2
  P1 -.quality check fails.-> P3
  FA -->|C1 proposals| ONB
  FB --> DSQL
  FB -->|C5 data-changed event| CORE
  ENG -->|C2 POST /dps/v1/values| VAL
  MON -->|C3 AI suggestions| AIS
  ENG --> BANK
  CORE --> CSQL & FILES & CPS & WF & NTF
  DIAPI & FA & FB --> DSQL & FILES
  FA & FB & AIS --> AOAI
```

`*` "Calculate" in Flow B computes **derived financial inputs only**, e.g. Total Debt = short-term + long-term debt. It does **not** compute covenant ratios against thresholds. That happens in Core (see [03](03-flows-design.md) §3).

## 3. Components and responsibilities

| Component | Owner | Responsibility | Must NOT do |
|---|---|---|---|
| **New UI (SPA)** | Our team (decision D1/D12) | One new UI for the combined product, built with React 18 + TypeScript + Mantine + TanStack. Reuses their formula builder, frequency picker, disposition and threshold components. Covers every screen in [07](07-feature-inventory.md) | Call DPS directly except through the gateway; hold business rules |
| API gateway | Platform / infra | SSO, token validation, identity header, rate limits | Business logic |
| **DPS API** | Our team | Accept documents, create jobs, report job status, serve values and suggestions | Decide compliance |
| **Flow A** | Our team | Agreement → covenant proposals with citations → publish to Core onboarding | Activate covenants |
| **Flow B** | Our team | Statement → validated line-item values per period → store → notify Core; advisory narrative | Compare against thresholds or set status |
| Background workers | Our team | Run flows from the queue, retries, timeouts, concurrency limits | Run in the web process |
| **ParserEngine** | Our team | Turn files into page text, OCR lines and table cells with page/coordinates. Primary pypdf + RapidOCR; pdfplumber for tables; Docling fallback for hard documents (see [03](03-flows-design.md) §8) | Send documents outside the bank network; use cloud document-AI services |
| **Covenant Core** | Their team | Library, onboarding, applied covenants, schedules, assessments, dispositions, notifications, audits, ops | Parse documents |
| SQL Server | Infra | Two schemas, one per service; no cross-schema writes | — |
| File service | Infra / their team | One store for all documents and evidence; both services reference `fileId` | — |
| Azure OpenAI | Infra | LLM for extraction help, narratives, AI suggestions; private endpoint; no training on data | — |

## 4. Data ownership

```mermaid
erDiagram
  CP ||--o{ DPS_DOCUMENT : "has"
  DPS_DOCUMENT ||--o{ DPS_JOB : "processed by"
  DPS_JOB ||--|| DPS_FLOW_STATE : "persists"
  DPS_DOCUMENT ||--o{ DPS_EXTRACTED_TERM : "Flow A output"
  DPS_DOCUMENT ||--o{ DPS_STATEMENT_VALUE : "Flow B output"
  DPS_EXTRACTED_TERM }o--|| COV_COVENANT_MASTER : "mapped to (by id)"
  COV_COVENANT_MASTER ||--o{ COV_APPLIED_COVENANT : "applied to CP"
  COV_APPLIED_COVENANT ||--o{ COV_ASSESSMENT : "append-only"
  COV_ASSESSMENT ||--o{ COV_COVENANT_DISPOSITION : "waiver/extension"
  COV_MEASURE }o--|| COV_DATA_SOURCE_TABLE : "reads"
  COV_DATA_SOURCE_TABLE }o--|| COV_DATA_SOURCE : "DPS registered as REST source"
```

| Data | Owner | Copy allowed? |
|---|---|---|
| Documents (bytes) | File service | No; reference by `fileId` |
| Document metadata, jobs, flow state, extracted terms, statement values | DPS schema | Core reads values only via C2 (staged into `cov_staging_value`, which is Core's design) |
| Covenant library, applied covenants, assessments, dispositions, audits | Core schema | DPS reads library via Core API (C4) only |
| CP / obligor / RM identity | CP service | Both cache read-only |

Keys: `cpId` (credit proposal) and `obligorId` from the CP service. Reporting period = `asOfDate` (period end date).

## 5. Main runtime interactions

| # | Interaction | Direction | Contract |
|---|---|---|---|
| C1 | Covenant proposals for onboarding | Core → DPS (pull, during onboarding) | `POST /dps/v1/recommendations` |
| C2 | Financial values for assessment | Core → DPS (Core's existing REST data source) | `POST /dps/v1/values` |
| C3 | AI status suggestions for breaches/manual items | Core → DPS | `POST /dps/v1/ai-suggestions` |
| C4 | Read library and measures for mapping | DPS → Core | `GET /api/v1/covenants`, `GET /api/v1/measures` |
| C5 | "New data for CP" event → re-assess | DPS → Core | **New Core endpoint** `POST /api/v1/data-events` |
| C6 | Files | Both → file service | Existing file-service API |

Full payloads: [04-api-contracts.md](04-api-contracts.md).

## 6. Deployment view

**Deployment target is not decided yet (D10).** Everything is built as Docker images with environment-based configuration and SQL Server, so it runs in the bank's Azure tenant (shown below as the reference) or on-premises (Kubernetes / VMs + on-prem SQL Server). The LLM provider sits behind an `LlmClient` interface in case Azure OpenAI is not reachable on-prem.


```mermaid
flowchart LR
  subgraph "Azure (bank tenant, private network)"
    AGW[App Gateway / APIM + WAF]
    subgraph AKS["Container platform (AKS or App Service)"]
      SPA2[SPA static site]
      CORE2[covenant-core ×2<br/>Quartz clustered]
      DIAPI2[dps-api ×2]
      DIW[dps-worker ×N<br/>Flows A/B · pypdf · RapidOCR · pdfplumber<br/>autoscale on queue depth]
      DIWH[dps-worker-heavy ×1–2<br/>Docling fallback · more CPU/RAM<br/>GPU optional later]
    end
    SQL[(Azure SQL / SQL Server<br/>schemas: cov, dps)]
    BLOB[(Blob / file service)]
    KV[(Key Vault — secrets)]
    AOAI2[(Azure OpenAI — private endpoint)]
    MON2[(App Insights / Log Analytics)]
  end
  Users --> AGW --> SPA2 & CORE2 & DIAPI2
  CORE2 & DIAPI2 & DIW & DIWH --> SQL
  DIAPI2 & DIW & DIWH & CORE2 --> BLOB
  DIW --> AOAI2
  CORE2 & DIAPI2 & DIW --> KV
  CORE2 & DIAPI2 & DIW --> MON2
```

- Two instances of Core need **Quartz clustering** (`isClustered: true`, JDBC store already used).
- DPS workers scale on queue depth. DPS API is stateless.
- Two worker pools. **dps-worker** runs every job with the fast local parsers (about 0.1 s per text page measured). **dps-worker-heavy** takes only jobs routed to the Docling fallback; it measured about 6–7 s per page on CPU, so it is kept separate so slow jobs never delay normal ones.
- **No document-AI cloud service.** Parsing and OCR run inside the worker containers. Only text that needs an LLM step goes to Azure OpenAI.
- Secrets live in Key Vault: DB, OpenAI, support token. No defaults in code.
- One correlation id passes through gateway → Core → DPS → worker → OpenAI for tracing.

## 7. Security architecture

| Control | Where | Fixes |
|---|---|---|
| Entra ID SSO, JWT validated at gateway | Gateway | Their T-SEC-1, our F-05 |
| JWT also validated inside each service (Spring Security resource server / FastAPI dependency) | Core, DPS | Defence in depth if the gateway is bypassed |
| Roles: RM, APPROVER, AUTHOR, SUPPORT, AUDITOR, SERVICE | Gateway claims → services | Waivers need APPROVER (maker-checker) |
| Portfolio check on **every** CP-scoped read and write | Core service layer | Their T-SEC-2, T-SEC-3 |
| Service-to-service auth (client credentials) between Core and DPS | Both | Values/recommendation APIs not open to browsers |
| No default secrets; startup fails if missing | Both | Their T-SEC-4 |
| CSP, output encoding; one source viewer in the new UI (pdf.js, escaped values) | SPA | Our F-04 |
| Upload limits: type, size, page count; parse in worker with timeout | DPS | Our A-09 |
| LLM: private endpoint, minimum text sent, prompts logged with retention limit | DPS | Our A-07/A-08 |
| Audit: every proposal, apply, assessment, disposition, override with user + source document | Both | Their append-only design + DPS run records |

## 8. What is retired from our app
| Retired | Replaced by |
|---|---|
| `web.py` UI, dashboard, reminders, HITL, waivers, evidence status | Core screens and rules |
| TinyDB JSON files | SQL Server `dps` schema + Core schema |
| CrewAI **Crew** (`crew.py`, 4 agents) | CrewAI **Flows** A and B (agent calls only inside steps that need an LLM) |
| Our compliance calculation and overall decision | Core gate ladder |
| Our 42-row covenant catalog | Core library (read via C4) |
| Chat / news | Optional phase 3, reading Core APIs |

## 9. Alternatives considered
| Option | Why not chosen |
|---|---|
| Patch our app to match Core's features | Months to reach their level; two engines would drift |
| Rewrite our extraction in Java inside Core | Loses Python PDF/OCR/LLM ecosystem; 4–6 months |
| Let Flow B also calculate and decide compliance | Two systems would give two answers for one covenant |
| Vector DB / RAG for documents | No retrieval problem found; documents are processed one at a time |
| Azure AI Document Intelligence / Azure Content Understanding for parsing | Excluded by decision. The local stack keeps documents inside the network at no per-page cost; Docling covers hard documents ([03](03-flows-design.md) §8) |
| Docling as the only parser | Same numbers as pypdf on our samples but about 45–55× slower on CPU; used as fallback only |
