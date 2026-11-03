# Production runbook

How to deploy, configure, watch, back up and roll back the Covenant Platform. The images are platform-neutral (decision D9 is still open), so steps are written for "a container platform" with Kubernetes and Azure examples where it helps.

## 1. What runs

| Service | Image | Port | Instances | State |
|---|---|---|---|---|
| Core | `core/backend/Dockerfile` | 9310 | 2+ (Quartz clustering) | SQL Server database `covenant` |
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
| `COVENANT_DB_POOL_SIZE` | default `20` | per instance |
| `COVENANT_JWT_ISSUER` | `https://login.microsoftonline.com/<tenant>/v2.0` | required |
| `COVENANT_JWT_AUDIENCE` | the shared API registration's app ID URI or client ID | required |
| `COVENANT_JWT_JWKS` | optional; defaults to the issuer's keys | |
| `COVENANT_SUPPORT_TOKEN` | random, 32+ characters | *secret*; required for `/api/v1/ops/**` (header `X-Support-Token`) |
| `COVENANT_DPS_BASE_URL` | DPS API's internal URL | |
| `COVENANT_DPS_TOKEN_URI` | `https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token` | Core's calls to DPS use client credentials; the token is refreshed before it expires |
| `COVENANT_DPS_CLIENT_ID`, `COVENANT_DPS_CLIENT_SECRET` | Core's client application | *secret* |
| `COVENANT_DPS_SCOPE` | `api://covenant-platform/.default` | |
| `COVENANT_DATA_SOURCES_ALLOWED_REST_BASE_URLS` | comma list of the bank's data API URL prefixes | empty means no REST source may set its own host |
| `COVENANT_DS_<NAME>` | password of each MSSQL data source, named by its `secretRef` | *secret* |
| `COVENANT_CLIENTS_CREDIT_PROPOSAL_BASE_URL` | bank service | also `..._ATTRIBUTES_...`, `..._DATA_API_...`, `..._WORKFLOW_...`, `..._ML_...`, `..._NOTIFICATION_...`, `..._FILE_UPLOAD_...`, `..._AI_SUGGESTION_...` |
| `COVENANT_AI_PROVIDER` | `external` (bank service) or `dps` | which service writes AI suggestions |

The daily breach run fires at 06:00 server time (`covenant.notifications.cron`); one instance runs it, thanks to Quartz clustering.

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
| `DPS_MAX_UPLOAD_MB` | default `50` | keep the UI's nginx `client_max_body_size` (60m) above it |

Worker IDs default to the host name; leave `DPS_WORKER_ID` unset.

## 4. Deploying a release

CI must be green on the commit (`master` requires the Core, DPS, UI and Docker image checks). Then, in this order:

1. **Back up** both databases and snapshot the document store (section 6).
2. **Core.** Roll out the new image. Flyway migrates the `covenant` database on start-up; with several instances, one migrates while the others wait on Flyway's lock. Check `GET /actuator/health` is `UP` on every instance.
   - The first deployment that includes migration V25 turns on `READ_COMMITTED_SNAPSHOT` `WITH ROLLBACK IMMEDIATE`, which rolls back open transactions on the database. Do it in a quiet period, or have a DBA run the statement beforehand (V25 then does nothing).
3. **DPS migrations.** Run once, before the new API and workers start:
   ```bash
   alembic upgrade head
   ```
   (in the DPS image, with `DPS_DATABASE_URL` set). The image's default command also runs it, which is fine for one API instance; with several, run it as a one-off job and start the API with `uvicorn --factory dps.main:create_app --host 0.0.0.0 --port 9400`.
4. **DPS API**, then **workers**. A worker that stops mid-job loses its lease, and the job is picked up again after `DPS_WORKER_LEASE_SECONDS` (900 s).
5. **UI.**
6. **Smoke test** (section 5).

## 5. Smoke test after a deployment

1. `GET https://<core>/actuator/health` returns `UP`, and `GET https://<dps>/health` reports the database `UP`.
2. Sign in to the UI as an RM. The portfolio loads.
3. `GET /api/v1/ops/health/data-sources` (role SUPPORT, header `X-Support-Token`) shows every data source reachable.
4. Upload a known agreement to a test CP; the job reaches `DONE` or `NEEDS_REVIEW` within a minute.
5. Open the open-breach report and download its Excel file.

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
| Core health | `GET /actuator/health` | not `UP` for 2 minutes |
| DPS health | `GET /health` on the DPS API | database `DOWN` |
| Daily breach run | Core log: `Daily breach run ... complete` / `Daily breach run failed` | no "complete" line by 07:00, or any "failed" |
| Notifications | table `cov_notification_audit`, `status = 'failed'` | any for today's run |
| DPS jobs | `GET /dps/v1/jobs?status=FAILED` (SUPPORT) | any failed job |
| DPS queue | `GET /dps/v1/jobs?status=QUEUED` | more than ~30 minutes of work (one worker ≈ 23 agreements/minute) |
| Data sources | `GET /api/v1/ops/health/data-sources` | any unreachable |
| Deadlock retries | Core log: `Transaction lost a deadlock` | many per minute (occasional is normal) |
| JVM and HTTP metrics | `GET /actuator/prometheus` | latency and error-rate thresholds of your choice |

`/actuator/prometheus` and `/actuator/metrics` need a valid token in `prod`. Scrape with a SERVICE token, or ask for a separate internal management port (not built yet).

Expected load, from `loadtest/README.md` (one laptop, everything on one machine): 50 concurrent users at 127 requests/s with every page under 1.1 s at p95; the daily run at 29 covenants/s (50,000 covenants ≈ 30 minutes).

## 8. Troubleshooting

| Symptom | First step |
|---|---|
| A CP's covenants show *Error* | `GET /api/v1/ops/cps/{cpId}/diagnostics`; then `POST /api/v1/ops/cps/{cpId}/reingest` or `POST /api/v1/ops/applied-covenants/{id}/reassess` |
| One assessment looks wrong | `GET /api/v1/ops/assessments/{assessmentId}/diagnostics` |
| Values look stale | `GET /api/v1/ops/cps/{cpId}/ingestion-state`; `DELETE` the same path clears the freshness gate |
| A DPS job is `FAILED` | read its `error` with `GET /dps/v1/jobs/{id}`, fix the cause, then `POST /dps/v1/jobs/{id}/retry` |
| A job sits in `NEEDS_REVIEW` | expected: a reviewer checks the extracted terms in the UI, then resumes it |
| Core will not start: `DEV_HEADER is not allowed with the prod profile` | remove `COVENANT_SECURITY_MODE=DEV_HEADER` |
| Core will not start: `needs ... issuer-uri and audience` | set `COVENANT_JWT_ISSUER` and `COVENANT_JWT_AUDIENCE` |
| Core will not start: `Could not turn on READ_COMMITTED_SNAPSHOT` | a DBA runs the statement it prints, then restart |
| DPS will not start: `needs DPS_JWT_ISSUER and DPS_JWT_AUDIENCE` | set both |
| Core logs `Token endpoint refused client credentials: HTTP 401` (or DPS jobs retry with the same message) | check the client ID and secret, and that the secret has not expired in Entra ID |
| Core gets 401 from DPS (or DPS from Core) with valid client credentials | the client application lacks the `SERVICE` app role, or the scope names a different API than the audience |
| Saving a data source fails with `DATA_SOURCE_PLAINTEXT_PASSWORD` | store the password in the secret store as `COVENANT_DS_<NAME>`, set `secretRef` on the source |
| A REST data source fails with `DATA_SOURCE_HOST_NOT_ALLOWED` | add its URL prefix to `COVENANT_DATA_SOURCES_ALLOWED_REST_BASE_URLS` |

Every ops endpoint is audited (`GET /api/v1/ops/audit`). `DELETE /api/v1/ops/cps/{cpId}/data` removes all of a CP's data and cannot be undone.

## 9. Rollback

- **Application only:** redeploy the previous images. Do this when the release added no migration, or only additive ones (new tables or columns), which the older code ignores.
- **With a breaking migration:** Flyway and Alembic only migrate forward. Restore the databases and document store from the backup taken in step 1 of section 4, then deploy the previous images. Anything written since the backup is lost, so decide quickly.
- Migration V25 (`READ_COMMITTED_SNAPSHOT`) can stay on with older code.

## 10. Known gaps before go-live

- **Metrics scraping** needs a token (section 7).
- **Not load-tested:** a separate database host, several Core instances under load, and a burst on the heavy (Docling) pool.
- **SQL Server data-source hosts** are not allowlisted (REST hosts are).
- The items that need people: golden-set label verification (D13), hosting (D9), Entra ID registrations, the Azure OpenAI deployment, and the bank's production endpoints.
