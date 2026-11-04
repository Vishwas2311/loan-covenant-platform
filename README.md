# Covenexa (Covenant Platform)

Combined product built from:
- the other team's **covenant-monitoring** app, which becomes the Core;
- our **covenant automatic tracking** app, which becomes the Document Processing Service (DPS).

Design, PRD and decisions live in [docs/integration/](docs/integration/README.md).

Testing it locally with real sign-in and Azure OpenAI: [docs/local-real-testing.md](docs/local-real-testing.md). Deploying and operating it: [docs/runbook.md](docs/runbook.md). Security settings: [SECURITY.md](SECURITY.md). Load-test results: [loadtest/README.md](loadtest/README.md).

## Layout

| Path | What | Stack |
|---|---|---|
| `core/backend` | **Covenant Core**: library, onboarding, scheduling, assessment, dispositions with maker-checker, monitoring, certificates, FX, reports, audit. Forked from covenant-monitoring | Java 17, Spring Boot 3, SQL Server / H2, Quartz, Flyway |
| `dps` | **Document Processing Service**: CrewAI Flows A (agreements) and B (statements), job workers, values API, advisory AI | Python 3.12, FastAPI, SQLAlchemy, Alembic, CrewAI 1.15 |
| `ui` | **One UI** for both services | React 18, TypeScript, Mantine 8, TanStack Query |
| `contracts` | OpenAPI contracts between Core, DPS and the UI (C1–C6) | OpenAPI 3.1 |
| `golden-set` | Labelled agreements and the extraction quality harness | Python |
| `deploy` | Dockerfiles are per service; `deploy/docker-compose.yml` runs the whole stack locally | Docker |
| `docs` | Architecture, PRD, decisions, verification | Markdown |

## Status

All PRD requirements FR-01 to FR-32 are implemented. Open items that need people, not code, are listed at the end.

| Area | What is built | Verified by |
|---|---|---|
| Security | Entra ID JWT (Core and DPS), `DEV_HEADER` for local use; roles RM, APPROVER, AUTHOR, SUPPORT, AUDITOR, SERVICE, ADMIN; `CpAccessGuard` on every CP endpoint (AUDITOR and SUPPORT read only); DPS delegates CP access to Core; per-user limits on AI questions and uploads; MSSQL data-source passwords come from the secret store through `secretRef` (a `COVENANT_DS_*` environment variable), and the `prod` profile refuses plaintext passwords | Core and DPS tests; live probes with real tokens (audit, 4 Nov 2026) |
| Flow A (agreements) | Parser (pypdf, RapidOCR for pages without text, Docling heavy pool); regex extraction ported from our app, page-aware with verbatim quotes; mapping to Core masters; citation, threshold-in-quote and formula checks; publish or hold for review; facility dates, cure periods, LTM, step-downs as review information | DPS tests; golden set (draft labels): recall 0.985, threshold accuracy 1.0 |
| Flow B (statements) | PDF, XLSX, CSV; 20 line items; unit detection to INR crore; derived inputs; data-quality checks; versioned values; C5 event to Core | DPS tests |
| Workers | `dps_job` queue with a 120 s lease renewed by a heartbeat, retries, STANDARD and HEAVY pools, SQL `FlowPersistence`, C5 outbox | DPS tests |
| Core integration | C1 extracted rows in onboarding (`EXTRACTED`, `EXTRACTED_CUSTOM`); C2 DPS as a REST data source with service credentials; C3 AI suggestions via DPS (switch); C5 data-events re-assess affected covenants | Core tests, DPS contract tests |
| Breach handling | Waiver/extension REQUESTED → APPROVED/REJECTED by another approver; switch in settings (default on); extension date ≥ today | Core tests |
| Certificates, currency, staleness | Certificate requirements per period (PENDING/RECEIVED/OVERDUE); reporting currency and dated FX; `max_age_days` per measure | Core tests |
| Reports | Period comparison with trend; Excel exports (comparison, library, open breaches); open-breach PDF; escalation flag; analytics by segment and sector; Ask AI over the caller's portfolio | Core, DPS and UI tests |
| UI | Portfolio, CP workspace, onboarding queue, document inbox with page view, library, reports, jobs, settings; phone layout; WCAG 2.1 AA in light and dark | 122 unit tests (87% lines); 52 Playwright tests on the full stack, axe-core included |
| Deployment | Images for Core, DPS (API, worker, heavy worker) and UI, all non-root; compose stack with SQL Server; Core `prod` profile with Quartz clustering; Core in `COVENANT_TIME_ZONE` (default Asia/Kolkata) | Stack run end to end; two clustered Core nodes; restore drill; Trivy, npm audit and pip-audit in CI (`security.yml`), nightly browser tests (`e2e.yml`) |

## Build and test

Core (JDK 17 + Maven 3.9):
```bash
cd core/backend && mvn -P no-owasp clean verify
```

DPS (Python 3.12):
```bash
cd dps && python -m venv .venv && .venv/Scripts/pip install -e ".[dev]" && .venv/Scripts/pytest
```

UI (Node 24):
```bash
cd ui && npm install && npm run test:coverage && npm run build
```

Whole stack:
```bash
cp deploy/.env.example deploy/.env
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up --build
```
UI at http://localhost:8080. The stack runs SQL Server, WireMock stubs for the bank services (`bank-stubs`), Core with the demo portfolio and its in-app ML threshold mock (`local-stack` profile), DPS (API, standard worker and the Docling heavy worker) and the UI. The heavy worker image is large; its models download into the `doclingcache` volume on the first heavy job. Pick a user in "Act as": `rm-anil` and `wrm-meera` (approver) own CP-1001…1007 and CP-2001…2005; `rm-priya` and `wrm-vikram` own the rest.

## Still needed before go-live (people, not code)

- D13: an analyst verifies the golden-set labels; the extraction gate is then measured on VERIFIED labels only.
- D9: choose the hosting platform; the images are platform-neutral.
- Entra ID app registrations for Core, DPS and the UI (issuer, audience, roles claim).
- Azure OpenAI deployment for advisory AI (`DPS_AI_MODEL`); without it the advisory features run on rules.
- The bank's CP service, file service and notification service endpoints for production.
