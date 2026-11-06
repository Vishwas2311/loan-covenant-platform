# Production runbook

How to deploy, configure, watch, back up and roll back the Covenant Platform. The images are platform-neutral (decision D9 is still open), so steps are written for "a container platform" with Kubernetes and Azure examples where it helps.

## 1. What runs

| Service | Image | Port | Instances | State |
|---|---|---|---|---|
| Core | `core/backend/Dockerfile` | 9310 (API, `/livez`, `/readyz`); 9320 internal actuator | 2+ (Quartz clustering) | SQL Server database `covenant` |
| DPS API | `dps/Dockerfile` | 9400 | 2+ | SQL Server database `dps`, document store |
| DPS worker (standard) | `dps/Dockerfile`, command `python -m dps.worker` | none | 1 per ~1,400 agreements/hour | same as API |
| DPS worker (heavy) | `dps/Dockerfile` built with `--build-arg INSTALL_HEAVY=1` | none | 1+ | same as API, plus a Docling model cache |
| UI | `ui/Dockerfile` (nginx) | 8080 | 2+ | none |

The UI's nginx serves the web app and proxies `/api/` to Core and `/dps/` to DPS. Set `CORE_UPSTREAM` and `DPS_UPSTREAM` (defaults `http://core:9310`, `http://dps-api:9400`). TLS ends at the platform's load balancer in front of the UI, which also sends HSTS.

External systems Core calls: the bank's credit-proposal, attributes, data API, workflow, ML threshold, notification, file-upload and AI-suggestion services. DPS calls Core, and Azure OpenAI when advisory AI is on.

## 2. Before the first deployment

1. **Entra ID app registrations:**
   - **One API registration shared by Core and DPS** (for example `api://covenant-platform`), with app roles `RM`, `APPROVER`, `AUTHOR`, `SUPPORT`, `AUDITOR`, `ADMIN` and `SERVICE`. It must be one audience: DPS passes the signed-in user's token on to Core (CP access checks, Ask AI), and Core accepts only tokens for its own audience.
   - **The UI** as a single-page app that requests that API's scope for the user.
   - **Two client applications**, one for Core and one for DPS, each with a client secret (or certificate) and the `SERVICE` app role on the API. They get tokens with the client-credentials grant for the service-to-service calls.
2. **Databases** on SQL Server: `covenant` and `dps`, each with a login that owns its database. `READ_COMMITTED_SNAPSHOT` must be on for `covenant` (Core's migration V25 turns it on if its login is allowed; otherwise it stops and prints the statement for a DBA). Turn it on for `dps` too:
   ```sql
   ALTER DATABASE covenant SET READ_COMMITTED_SNAPSHOT ON WITH ROLLBACK IMMEDIATE;
   ALTER DATABASE dps SET READ_COMMITTED_SNAPSHOT ON WITH ROLLBACK IMMEDIATE;
   ```
3. **Document store** for DPS: one volume shared read-write by the DPS API and every worker (for example Azure Files), mounted at `DPS_STORAGE_DIR`. It holds the original agreements and statements, so it needs backups like a database.
4. **Secret store** entries for everything marked *secret* in section 3.
5. **The bank's service URLs** for every Core client in section 3.

## 3. Configuration

Both services refuse to start with an unsafe setting, so a mistake shows up at deploy time.

### Core

| Variable | Value | |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` | JWT sign-in, no H2, no plaintext data-source passwords, Quartz clustering |
| `COVENANT_DB_URL` | `jdbc:sqlserver://<host>:1433;databaseName=covenant;encrypt=true` | |
| `COVENANT_DB_USER`, `COVENANT_DB_PASSWORD` | Core's database login | *secret* |
| `COVENANT_DB_POOL_SIZE` | default `20` | per instance; local-stack reads it too |
| Container memory | `CORE_MEM_LIMIT` in `deploy/docker-compose.yml`, default `2g` | always set a limit: the image sizes the heap at 75% of the memory it sees, and without a limit Core can starve SQL Server on a shared host |
| `COVENANT_TASKS_SYNCCRON` (property `covenant.tasks.sync-cron`) | default `0 0/5 * * * ?` | background task sync (one node at a time); bank-wide task lists read what it writes |
| `COVENANT_JWT_ISSUER` | `https://login.microsoftonline.com/<tenant>/v2.0` | required |
| `COVENANT_JWT_AUDIENCE` | the shared API registration's app ID URI or client ID | required |
| `COVENANT_JWT_JWKS` | optional; defaults to the issuer's keys | |
| `COVENANT_SUPPORT_TOKEN` | random, 32+ characters | *secret*; required for `/api/v1/ops/**` (header `X-Support-Token`) |
| `COVENANT_DPS_BASE_URL` | DPS API's internal URL | |
| `COVENANT_DPS_TOKEN_URI` | `https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token` | Core's calls to DPS use client credentials; the token is refreshed before it expires |
| `COVENANT_DPS_CLIENT_ID`, `COVENANT_DPS_CLIENT_SECRET` | Core's client application | *secret* |
| `COVENANT_DPS_SCOPE` | `api://covenant-platform/.default` | |
| `COVENANT_DATA_SOURCES_ALLOWED_REST_BASE_URLS` | comma list of the bank's data API URL prefixes | empty means no REST source may set its own host |
| `COVENANT_DATA_SOURCES_ALLOWED_JDBC_HOSTS` | comma list of the bank's SQL Server host names that hold data-source tables | empty means no MSSQL data source can connect |
| `COVENANT_MANAGEMENT_PORT` | default `9320` | actuator port; internal only, never behind the public load balancer |
| `COVENANT_DS_<NAME>` | password of each MSSQL data source, named by its `secretRef` | *secret* |
| `COVENANT_CLIENTS_CREDIT_PROPOSAL_BASE_URL` | bank service | also `..._ATTRIBUTES_...`, `..._DATA_API_...`, `..._WORKFLOW_...`, `..._ML_...`, `..._NOTIFICATION_...`, `..._FILE_UPLOAD_...`, `..._AI_SUGGESTION_...` |
| `COVENANT_AI_PROVIDER` | `external` (bank service) or `dps` | which service writes AI suggestions |
| `COVENANT_CLIENTS_<SERVICE>_OAUTH_TOKEN_URI`, `..._OAUTH_CLIENT_ID`, `..._OAUTH_CLIENT_SECRET`, `..._OAUTH_SCOPE` | per bank service (`NOTIFICATION`, `CREDIT_PROPOSAL`, `ATTRIBUTES`, `DATA_API`, `WORKFLOW`, `ML`, `FILE_UPLOAD`, `AI_SUGGESTION`) | *secret*; when set, Core fetches and refreshes a client-credentials token for that service. `..._SERVICE_TOKEN` is the fixed-token alternative |
| `COVENANT_ESCALATION_RECIPIENTS` | comma-separated, for example the credit head | FR-31 digest of breaches unhandled longer than `COVENANT_ESCALATION_AFTER_DAYS` (default 14), sent after the daily run with template `Cov_Escalation`; empty switches it off |

The daily breach run fires at 06:00 server time (`covenant.notifications.cron`); one instance runs it, thanks to Quartz clustering.

Covenant schedules: a frequency's default cron (`0 0 2 * * ?`) fires at a second and minute within that hour fixed per covenant, so a large book spreads over the hour. At start-up Core rebuilds any covenant trigger still bunched at the top of the hour or in another time zone and logs `Realigned N covenant triggers`.

Manual daily run (Support, "Send today's e-mails now"): `POST /api/v1/monitoring/notification-runs` answers 202 with a run id; `GET /api/v1/monitoring/notification-runs/{runId}` shows RUNNING, DONE with counts, or FAILED with the reason. A second start while one is running returns that run.

Portfolio pages keep their aggregates for up to 30 seconds; any write on the same Core instance drops them at once. With two instances, a write on one shows on the other within 30 seconds.

Task lists: a read by an RM or approver syncs that person's CPs first (up to 300 CPs). Bank-wide roles and CPs nobody opens are kept current by the background task sync: changed CPs within 5 minutes, others every 30 minutes.

### DPS (API and workers)

| Variable | Value | |
|---|---|---|
| `DPS_ENV` | `prod` | refuses `DEV_HEADER` sign-in |
| `DPS_AUTH_MODE` | `JWT` (default) | |
| `DPS_JWT_ISSUER`, `DPS_JWT_AUDIENCE` | the shared API registration (same audience as Core) | required |
| `DPS_JWT_JWKS_URL` | `https://login.microsoftonline.com/<tenant>/discovery/v2.0/keys` | |
| `DPS_DATABASE_URL` | `mssql+pymssql://<user>:<url-encoded password>@<host>:1433/dps` | *secret* |
| `DPS_STORAGE_DIR` | the shared document store mount | |
| `DPS_CORE_BASE_URL` | Core's internal URL | |
| `DPS_CORE_TOKEN_URL` | `https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token` | DPS's calls to Core use client credentials, refreshed before expiry |
| `DPS_CORE_CLIENT_ID`, `DPS_CORE_CLIENT_SECRET` | DPS's client application | *secret* |
| `DPS_CORE_SCOPE` | `api://covenant-platform/.default` | |
| `DPS_CORE_DATA_SOURCE_ID` | `d5000000-0000-0000-0000-000000000d95` | the DPS data source Core's migration creates |
| `DPS_WORKER_POOL` | `STANDARD` or `HEAVY` (workers only) | |
| `DPS_AI_MODEL` | for example `azure/gpt-4o`, with `AZURE_API_KEY`, `AZURE_API_BASE`, `AZURE_API_VERSION` | empty: advisory AI runs on rules |
| `DPS_STATEMENT_AI_CHECK` | default `false` | `true` (with `DPS_AI_MODEL` set): the model also reads each statement PDF's pages and a covenant input it reads differently holds the statement for review (`SECOND_READER_DIFFERS`). One model call per statement; the model's numbers are never stored |
| `DPS_AI_TIMEOUT_SECONDS` | default `60` | longest wait for one model answer; keep it under the gateway's `proxy_read_timeout` (120 s), so a slow model gives the rules answer instead of a 504 |
| `DPS_JWT_LEEWAY_SECONDS` | default `30` | clock difference allowed between Entra ID and DPS when checking a token's times (Core allows 60 s) |
| `DPS_MAX_UPLOAD_MB` | default `50` | keep the UI's nginx `client_max_body_size` (60m) above it |
| `DPS_MAX_PAGES` | default `500` | longer PDFs are refused at upload (FR-01) |
| `DPS_AI_PROMPT_COST_PER_1K`, `DPS_AI_COMPLETION_COST_PER_1K` | the bank's Azure OpenAI price per 1,000 tokens | every model call's tokens are recorded; with these set, its cost too. Support and admins read totals at `GET /dps/v1/ai-usage?days=30` |

On SQL Server, DPS refuses to start unless `READ_COMMITTED_SNAPSHOT` is on for its database, and prints the statement a DBA must run.

DPS asks Core whether a user may see a CP and remembers a "yes" for 30 seconds; a revoked CP is refused again within that time. When Core cannot answer, DPS returns 503 `ACCESS_CHECK_UNAVAILABLE` (retry), never "not found".

Worker IDs default to the host name; leave `DPS_WORKER_ID` unset.

## 4. Deploying a release

CI must be green on the commit (`master` requires the Core, DPS, UI and Docker image checks). Then, in this order:

1. **Back up** both databases and snapshot the document store (section 6).
2. **Core.** Roll out the new image. Flyway migrates the `covenant` database on start-up; with several instances, one migrates while the others wait on Flyway's lock. Check `GET /readyz` returns 200 on every instance.
   - The first deployment that includes migration V25 turns on `READ_COMMITTED_SNAPSHOT` `WITH ROLLBACK IMMEDIATE`, which rolls back open transactions on the database. Do it in a quiet period, or have a DBA run the statement beforehand (V25 then does nothing).
3. **DPS migrations.** Run once, before the new API and workers start:
   ```bash
   alembic upgrade head
   ```
   (in the DPS image, with `DPS_DATABASE_URL` set). The image's default command also runs it, which is fine for one API instance; with several, run it as a one-off job and start the API with `uvicorn --factory dps.main:create_app --host 0.0.0.0 --port 9400`.
4. **DPS API**, then **workers**. A worker that stops mid-job loses its lease, and the job is picked up again after `DPS_WORKER_LEASE_SECONDS` (120 s). A live worker renews the lease every third of that, so a long job is never taken over while it runs.
5. **UI.**
6. **Smoke test** (section 5).

## 5. Smoke test after a deployment

1. `GET /readyz` on Core returns 200, and `GET /health` on the DPS API reports the database `UP`.
2. Sign in to the UI as an RM. The portfolio loads.
3. `GET /api/v1/ops/health/data-sources` (role SUPPORT, header `X-Support-Token`) shows every data source reachable.
4. Upload a known agreement to a test CP; the job reaches `DONE` or `NEEDS_REVIEW` within a minute.
5. Open the open-breach report and download its Excel file.

## 5b. Testing e-mail locally

`deploy/docker-compose.mail.yml` replaces the bank's notification service with a small test bridge that turns
each notification into a real e-mail and delivers it to Mailpit, a local inbox:

```bash
docker compose -f docker-compose.yml -f docker-compose.real.yml -f docker-compose.mail.yml up -d
```

Open http://localhost:8025 to read the RM summaries, borrower reminders, saved reports and escalation digests
(escalations go to `credit.head@covenant.local`). Nothing leaves the machine. Never use this file in production.

## 5c. Two Core nodes locally

`deploy/docker-compose.scale.yml` runs two Core nodes behind the gateway, with Quartz clustered as in the prod
profile:

```bash
docker compose -f docker-compose.yml -f docker-compose.real.yml -f docker-compose.scale.yml up -d --scale core=2
```

Core's host ports are not published in this mode; use the gateway on port 8080. Each node keeps its own
30-second portfolio cache.

## 5a. Time zone

Core runs in `COVENANT_TIME_ZONE` (default `Asia/Kolkata`, set in the image). The 06:00 daily run, due dates and "today" follow it, not the container's UTC clock. Set it on the Core container if the bank works in another zone. The daily-run trigger is re-registered with the zone at every start; per-covenant assessment triggers take it when they are next created or rescheduled.

## 6. Backups and restore

| What | How | Keep |
|---|---|---|
| `covenant` database | full nightly, log backups every 15 minutes | per the bank's retention policy |
| `dps` database | same | same |
| Document store | daily snapshot | same; it holds the source documents |

Restore both databases and the document store to the **same point in time**: DPS rows point at files by content hash, and Core's extracted covenants point at DPS documents. After a restore, start Core first (Quartz reloads its jobs from the database), then DPS, then check section 5.

## 7. Monitoring and alerts

| Signal | Where | Alert when |
|---|---|---|
| Core health | `GET /readyz` (port 9310), or `GET /actuator/health` on port 9320 for details | not 200 / `UP` for 2 minutes |
| DPS health | `GET /health` on the DPS API | database `DOWN` |
| Daily breach run | Core log: `Daily breach run ... complete` / `Daily breach run failed` | no "complete" line by 07:00, or any "failed" |
| Notifications | table `cov_notification_audit`, `status = 'failed'` | any for today's run |
| DPS jobs | `GET /dps/v1/jobs?status=FAILED` (SUPPORT) | any failed job |
| DPS queue | `GET /dps/v1/jobs?status=QUEUED` | more than ~30 minutes of work (one worker ≈ 23 agreements/minute) |
| Data sources | `GET /api/v1/ops/health/data-sources` | any unreachable |
| Deadlock retries | Core log: `Transaction lost a deadlock` | many per minute (occasional is normal) |
| JVM, HTTP, database pool metrics | `GET /actuator/prometheus` on port 9320 | latency and error-rate thresholds of your choice |

The actuator runs on the internal port 9320 and needs no token there; keep that port off the public load balancer and open it only to the monitoring network. Port 9310 serves just `/livez` and `/readyz` publicly.

Expected load, from `loadtest/README.md` (one laptop, everything on one machine): 50 concurrent users at 127 requests/s with every page under 1.1 s at p95; the daily run at 29 covenants/s (50,000 covenants ≈ 30 minutes).

## 8. Troubleshooting

| Symptom | First step |
|---|---|
| A CP's covenants show *Error* | `GET /api/v1/ops/cps/{cpId}/diagnostics`; then `POST /api/v1/ops/cps/{cpId}/reingest` or `POST /api/v1/ops/applied-covenants/{id}/reassess` |
| One assessment looks wrong | `GET /api/v1/ops/assessments/{assessmentId}/diagnostics` |
| Values look stale | `GET /api/v1/ops/cps/{cpId}/ingestion-state`; `DELETE` the same path clears the freshness gate |
| An assessment says "Data may be stale: the latest refresh of … failed" | the source (often DPS) could not be read, so Core used its stored copy. Fix the source; the next assessment refreshes and the note goes |
| A DPS job is `FAILED` | read its `error` with `GET /dps/v1/jobs/{id}`, fix the cause, then `POST /dps/v1/jobs/{id}/retry` |
| A job sits in `NEEDS_REVIEW` | expected: a reviewer checks the extracted terms in the UI, then resumes it |
| Core will not start: `DEV_HEADER is not allowed with the prod profile` | remove `COVENANT_SECURITY_MODE=DEV_HEADER` |
| Core will not start: `needs ... issuer-uri and audience` | set `COVENANT_JWT_ISSUER` and `COVENANT_JWT_AUDIENCE` |
| Core will not start: `Could not turn on READ_COMMITTED_SNAPSHOT` | a DBA runs the statement it prints, then restart |
| DPS will not start: `needs DPS_JWT_ISSUER and DPS_JWT_AUDIENCE` | set both |
| Core logs `Token endpoint refused client credentials: HTTP 401` (or DPS jobs retry with the same message) | check the client ID and secret, and that the secret has not expired in Entra ID |
| Core gets 401 from DPS (or DPS from Core) with valid client credentials | the client application lacks the `SERVICE` app role, or the scope names a different API than the audience |
| Saving a data source fails with `DATA_SOURCE_PLAINTEXT_PASSWORD` | store the password in the secret store as `COVENANT_DS_<NAME>`, set `secretRef` on the source |
| A data source fails with `DATA_SOURCE_HOST_NOT_ALLOWED` | REST: add its URL prefix to `COVENANT_DATA_SOURCES_ALLOWED_REST_BASE_URLS`; MSSQL: add its server to `COVENANT_DATA_SOURCES_ALLOWED_JDBC_HOSTS` |

Every ops endpoint is audited (`GET /api/v1/ops/audit`). `DELETE /api/v1/ops/cps/{cpId}/data` removes all of a CP's data and cannot be undone.

## 9. Rollback

- **Application only:** redeploy the previous images. Do this when the release added no migration, or only additive ones (new tables or columns), which the older code ignores.
- **With a breaking migration:** Flyway and Alembic only migrate forward. Restore the databases and document store from the backup taken in step 1 of section 4, then deploy the previous images. Anything written since the backup is lost, so decide quickly.
- Migration V25 (`READ_COMMITTED_SNAPSHOT`) can stay on with older code.

## 10. Known gaps before go-live

- **Not load-tested:** a separate database host and a burst on the heavy (Docling) pool. Two Core instances were tested on one host only (`deploy/docker-compose.scale.yml`).
- The items that need people: golden-set label verification (D13), hosting (D9), Entra ID registrations, the Azure OpenAI deployment, and the bank's production endpoints.
