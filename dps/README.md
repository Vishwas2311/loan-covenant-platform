# Document Processing Service (DPS)

Turns loan agreements and financial statements into covenant **proposals** and financial **values** for Covenant Core. It never decides compliance. Contract: [`../contracts/dps-api.yaml`](../contracts/dps-api.yaml).

## What it does

| Part | Detail |
|---|---|
| API | Documents (upload, idempotent, link to N CPs, original file), extracted terms review, jobs (status, resume, support list and retry), manual values, C1 recommendations, C2 values, catalog, C3 AI suggestions, statement narrative, Ask AI |
| Flow A | Parse → Extract → Normalize → Validate → Publish / hold for review (`dps.flows.intake`) |
| Flow B | Parse → Extract/Calculate → Validate → Report / hold (`dps.flows.statement`); C5 event per linked CP |
| Workers | `python -m dps.worker`; `DPS_WORKER_POOL=HEAVY` (with `.[heavy]`) runs Docling for scans |
| Advisory AI | Rules by default; set `DPS_AI_MODEL` (for example `azure/<deployment>`) to use an LLM through CrewAI. Always labelled advisory |
| Auth | Entra ID JWT or `DEV_HEADER`; CP access delegated to Core |

## Run locally

```bash
python -m venv .venv
.venv/Scripts/pip install -e ".[dev]"   # includes the worker extras (CrewAI, RapidOCR)
cp .env.example .env               # DEV_HEADER mode, SQLite
.venv/Scripts/alembic upgrade head
.venv/Scripts/uvicorn --factory dps.main:create_app --port 9400
.venv/Scripts/python -m dps.worker   # second terminal: processes uploaded documents
```

Call with dev headers, for example `X-User: rm-anil` and `X-Roles: RM`.

## Test

```bash
.venv/Scripts/pytest          # coverage gate 90%
.venv/Scripts/ruff check src tests
```
