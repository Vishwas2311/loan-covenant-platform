# Covenant Monitoring — Operations Runbook

## Service overview

`covenant-service` (Spring Boot, Java 17, port 9310) backed by Microsoft SQL Server. Frontend served separately as a static bundle (`covenant-frontend`, Vite build).

External dependencies:
- Credit Proposal Module (`covenant.clients.credit-proposal.base-url`)
- Attributes API (`covenant.clients.attributes.base-url`)
- Data API (`covenant.clients.data-api.base-url`)
- Workflow Service (`covenant.clients.workflow.base-url`)

## Health checks

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Liveness + readiness composite. `DOWN` → restart. |
| `GET /actuator/info` | Build metadata (commit, version). |
| `GET /actuator/metrics/jvm.memory.used` | JVM memory. |
| `GET /actuator/metrics/quartz.scheduler.jobs.executed` | Quartz jobs ran since last restart. |
| `GET /actuator/prometheus` | Prometheus scrape target. |

## Configuration

All client base URLs are env-overrideable via Spring's relaxed binding:

```
COVENANT_CLIENTS_CREDITPROPOSAL_BASEURL=https://...
COVENANT_CLIENTS_ATTRIBUTES_BASEURL=https://...
COVENANT_CLIENTS_DATAAPI_BASEURL=https://...
COVENANT_CLIENTS_WORKFLOW_BASEURL=https://...
```

Database URL via:

```
SPRING_DATASOURCE_URL=jdbc:sqlserver://host:1433;databaseName=covenant
SPRING_DATASOURCE_USERNAME=...
SPRING_DATASOURCE_PASSWORD=...
```

## Schema management

Flyway manages the `covenant_master`, `applied_covenant`, `monitoring_schedule`, `assessment`, `expression_change_log` tables (V1…V5). Quartz tables for production MSSQL must be initialised separately the first time — vendor the `tables_sqlServer.sql` from Quartz's distribution and apply once (or set `spring.quartz.jdbc.initialize-schema=always` for the first deploy and switch to `never` thereafter).

For the current build the in-memory Quartz job store (`spring.quartz.job-store-type=memory`) is wired — switch to `jdbc` when migrating to production MSSQL.

## Deployment (blue-green)

1. Build: `mvn -P fast package` (skips OWASP NVD scan locally — CI runs full SCA).
2. Image: `docker build -t covenant-service:<git-sha> .` (Dockerfile to be added per platform).
3. Deploy green:
   ```
   kubectl apply -f k8s/covenant-service-green.yaml
   kubectl rollout status deployment/covenant-service-green
   ```
4. Smoke: `curl https://green.covenant.internal/actuator/health` → expect `UP`.
5. Switch traffic: flip service selector from `blue` → `green`.
6. Ramp: 5% → 25% → 100% over the first day via the ingress weight.
7. Keep blue running for 24h for rollback safety.

## Rollback

Flip ingress weight back to blue and investigate. **Do not** redeploy until root cause is known.

## Common incidents

### Scheduled assessments not firing
1. Check `/actuator/health` — DB or scheduler down?
2. Inspect Quartz table rows (or in-memory: check logs for "Scheduler covenant-scheduler started").
3. Confirm `applied_covenant.status = 'ACTIVE'` for the CP.
4. Confirm `monitoring_schedule` row exists with `status = 'SCHEDULED'`.

### Assessments stuck on ERROR
Look at `assessment.error_message`. Common causes:
- `Missing values:` — Data API didn't return all keys requested. Coordinate with Data API team.
- `DATA_API_FAILURE:` — connectivity issue. Verify base URL config.
- `Expression must evaluate to a boolean` — the covenant's `rule_expression` evolved to return a non-boolean. Fix via library admin UI.

### Workflow not getting kicked off
Inspect `assessment.workflow_error`. Empty body or 5xx from workflow service — coordinate with workflow team.

## Capacity baselines

- Schema fits 1M+ covenant master rows without strain.
- Search query p95 under 500ms with warm cache for 1K+ rows (verified by `PerfSmokeTest`).
- Quartz default thread pool size: 10 — bump via `org.quartz.threadPool.threadCount` if assessment lag observed.

## Security

- Admin-entered SpEL expressions are run inside `SimpleEvaluationContext.forReadOnlyDataBinding()` — no type references, constructors, or arbitrary method invocation possible.
- Denylist of dangerous SpEL tokens (`T(`, `new`, `.class`, `@`, `?.`, `?[`) applied at save time.
- Every covenant edit (when `ruleExpression` changes) writes to `expression_change_log` — immutable forensic trail.
- Identity propagated via `X-User` header from the gateway; defaults to `system` if absent.
