# Covenant Monitoring — project guide

Spring Boot 3.3 (Java 17) backend + React 18 / Mantine v8 (Vite) frontend for authoring covenants,
recommending + applying them to credit proposals (CPs), and assessing compliance. Covenant
conditions are written in a measure-based formula DSL (see `backend/.../formula`) and evaluated
against time-series data via windowed aggregation.

## Conventions

### Database table naming — `cov_` prefix (REQUIRED for every application table)
**Every application/audit table MUST be named with a `cov_` prefix.** This applies to new tables
*and* the pre-existing ones, which were all renamed: `cov_covenant_master`, `cov_measure`,
`cov_data_source`, `cov_data_source_table`, `cov_applied_covenant`, `cov_assessment`,
`cov_covenant_recommendation`, `cov_monitoring_schedule`, `cov_applied_covenant_audit`,
`cov_expression_change_log`, `cov_credit_proposal_cache`. The `@Table` name on
each JPA entity matches its migration table exactly (Hibernate runs with `ddl-auto: validate`).

**Not** prefixed (framework-/infra-managed, intentionally left as-is): the 11 Quartz `QRTZ_*` tables
(`org.quartz.jobStore.tablePrefix=QRTZ_`), Flyway's `flyway_schema_history`, and the dev-only demo
seed table `cp_metrics_demo` — the latter simulates an *external* customer data source (registered
as the "Demo MSSQL Financials" source), so it is not part of our schema.

### Flyway migrations
- Prod migrations: `backend/src/main/resources/db/migration` (`V1`, `V3`…). Dev-only demo seed:
  `backend/src/main/resources/db/demo` (`V10`, `V20`…) — loaded only under the `dev` profile.
- DDL must be **MSSQL-compatible and also run on H2 in `MODE=MSSQLServer`** (dev/test use H2;
  prod targets SQL Server). Use `IDENTITY`, `DATETIME2`, `BIT`, named PK/UK/CK/FK constraints.
- Never edit a released migration; add a new versioned file.

### Quality gates
- Backend: JaCoCo **0.95 line / 0.90 branch** (bundle), SpotBugs + FindSecBugs (Max/Low),
  OWASP dependency-check (`failBuildOnCVSS 7`). DTOs (`*Request`/`*Response`/`dto/**`) are excluded
  from coverage. `IMPROPER_UNICODE` false-positives on ASCII case-folding are excluded per-class in
  `spotbugs-exclude.xml`.
- Frontend: ESLint `--max-warnings=0`, a security ESLint config (`sast`), `audit-ci`, Vitest
  coverage (95 stmt / 85 branch / 75 func / 95 line; `hooks/**` and `types/**` excluded).

## Build & test
Nothing is on `PATH` in this workspace — use absolute tool paths (JDK `…\java\jdk-17.0.19+10`,
Maven `…\tools\maven\bin\mvn.cmd`, Node `…\tools\node`), and set `JAVA_HOME` before Maven.

- Backend fast loop: `mvn -P fast test` (skips ITs/SpotBugs/OWASP).
- Backend full gate (no NVD network): `mvn -P no-owasp verify`.
- Run app (dev demo data): `mvn -P fast "-Dspring-boot.run.profiles=dev" spring-boot:run` (port 9310).
- Frontend: `npm run type-check`, `npm run test`, `npm run lint`, `npm run build` (in `frontend/`).

## Specs
Product specifications live in [`_userstories/`](_userstories/README.md) — read the relevant story
before implementing a covenant/recommendation/assessment change.
