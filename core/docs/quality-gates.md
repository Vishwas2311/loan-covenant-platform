# Quality Gates — Per-Sprint Enforcement

Every sprint exits only when **all** gates are green. The CI pipeline (`/.github/workflows/ci.yml`) enforces these automatically; no PR merges to `main` without all gates passing.

## Gate Definitions

### G1 — Test Coverage (95%)

| Layer | Tool | Threshold | Enforced In |
|---|---|---|---|
| Backend Java | JaCoCo | **Line ≥ 95%, Branch ≥ 90%** | `mvn verify` (`jacoco:check` rule) |
| Frontend unit / component | Vitest + v8 coverage | **Statements ≥ 95%, Lines ≥ 95%, Branches ≥ 85%, Functions ≥ 75%** | `npm run test:coverage` (`vitest.config.ts` thresholds) |

> **Why statements/lines hold at 95%, branches at 85%, functions at 80%**: statement and line coverage are the most meaningful "how much of my code executed" signals — those remain at 95%. Branch and function coverage on UI built with Mantine v8 + TanStack Query are pulled down by anonymous callbacks the test runner counts as separate functions, and by branches inside Mantine internals that don't fully render in JSDOM (Combobox dropdowns, Stepper internals, ScrollArea). The same interactions are exercised end-to-end via Playwright in `tests/e2e/`. If a future module bakes business logic into anonymous callbacks, we revisit.
| Frontend E2E | Playwright | All critical user flows covered (P0 stories) | `npm run test:e2e` |

**Excluded from coverage**:
- Application bootstrap (`CovenantApplication.java`, `main.tsx`)
- Pure-data DTOs / records (`*Dto`, `*Request`, `*Response`)
- Spring `@Configuration` classes
- Type-only TS files (`*.types.ts`)

### G2 — SCA (Software Composition Analysis)

| Layer | Tool | Policy | Enforced In |
|---|---|---|---|
| Backend | OWASP Dependency-Check | **Fail on CVSS ≥ 7.0** in production-scope dependencies | `mvn dependency-check:check` |
| Frontend | `npm audit` via `audit-ci` | **Fail on any HIGH or CRITICAL** advisory | `npm run audit:ci` |

Suppressions only via `owasp-suppressions.xml` (BE) or `.audit-ci.json` (FE), each with a dated `notes` justification reviewed quarterly.

### G3 — SAST (Static Application Security Testing)

| Layer | Tool | Policy | Enforced In |
|---|---|---|---|
| Backend | SpotBugs + **FindSecBugs** plugin | **Effort=Max, Threshold=Low** — fail on any reported bug | `mvn spotbugs:check` |
| Frontend | `eslint-plugin-security` + Semgrep (rules: `p/security-audit`, `p/owasp-top-ten`, `p/react`, `p/typescript`) | Fail on any finding at `ERROR` severity | `npm run sast` |

### G4 — SonarQube Quality Gate

The SonarQube project quality gate must pass: **0 New Bugs, 0 New Vulnerabilities, 0 New Security Hotspots (or all reviewed), Code Smells per line ≤ baseline, Duplication ≤ 3%, Coverage on New Code ≥ 95%**.

Configured via `sonar-project.properties` / Maven plugin invocation in CI; `-Dsonar.qualitygate.wait=true` makes the CI job block on the gate result.

### G5 — Code Style / Static Checks

| Layer | Tool | Enforced In |
|---|---|---|
| Backend | (Optional) Checkstyle + PMD — wired in Sprint 8 if scope budget allows | `mvn verify` |
| Frontend | ESLint (typescript-eslint + react + a11y rules) + Prettier | `npm run lint` (CI fails on any warning) |

### G6 — Build & Boot Smoke

- Backend: `mvn package` produces an executable JAR; smoke test starts the app and hits `/actuator/health`.
- Frontend: `npm run build` produces a Vite bundle; `vite preview` boots without console errors.

---

## Sprint Exit Checklist

Tech Lead signs off per sprint after confirming each item:

- [ ] **G1** Backend coverage ≥ 95% line, ≥ 90% branch (JaCoCo report attached to sprint review)
- [ ] **G1** Frontend coverage ≥ 95% across statements / branches / functions / lines
- [ ] **G1** Playwright suite green; new P0 stories have E2E coverage
- [ ] **G2** OWASP Dependency-Check report — no findings ≥ CVSS 7.0
- [ ] **G2** `npm audit --production` — no high/critical advisories
- [ ] **G3** SpotBugs + FindSecBugs — zero findings
- [ ] **G3** Frontend SAST — zero ERROR-severity findings
- [ ] **G4** SonarQube quality gate **passed** (link to project dashboard in sprint notes)
- [ ] **G5** ESLint + Prettier — zero warnings
- [ ] **G6** Backend + Frontend boot smoke tests green
- [ ] Demo recorded and sprint notes published
- [ ] Risk register reviewed; any new risks added; old risks status-updated

If **any** item is red, the sprint cannot close. Either:
1. Fix in-sprint (preferred), or
2. Roll over with explicit risk-acceptance from PO + Tech Lead.

---

## Coverage Strategy

Hitting 95% coverage without writing useless tests requires disciplined exclusion of code that is verified by other means:

- **Bootstrap classes**: Excluded because tested indirectly by `@SpringBootTest` boot tests.
- **Spring `@Configuration`**: Excluded because tested indirectly by integration tests that exercise the wired beans.
- **Pure-data DTOs**: Excluded because they have no logic; record types provide compiler guarantees.
- **Jackson-serialised types**: Verified via `@JsonTest` slice tests, but excluded from coverage if logic-free.
- **Quartz `Job` implementations**: Required to have a dedicated test class (not excluded).

We **don't** exclude:
- Services, controllers, repositories with custom queries, validators, mappers with logic, evaluators, schedulers, exception handlers, audit listeners.

---

## Tooling Versions (pinned)

| Tool | Version | Where |
|---|---|---|
| JaCoCo | 0.8.12 | `pom.xml` |
| SpotBugs | 4.8.6 | `pom.xml` |
| FindSecBugs plugin | 1.13.0 | `pom.xml` |
| OWASP Dependency-Check | 10.0.4 | `pom.xml` |
| Sonar Maven plugin | 4.0.0.4121 | `pom.xml` |
| Vitest | latest 3.x | `package.json` |
| Playwright | latest 1.x | `package.json` |
| eslint-plugin-security | latest | `package.json` |
| audit-ci | latest | `package.json` |
| semgrep | container in CI | `ci.yml` |

Renovate / Dependabot keeps these moving forward; SCA gate enforces no regressions slip in.

---

## Cost / Time Budget per Sprint

The quality gates add ~12–18 minutes to CI:

| Gate | Approx duration |
|---|---|
| Backend build + unit tests | 2 min |
| Backend JaCoCo report | 30 sec |
| Backend SpotBugs | 1 min |
| OWASP DC (cached NVD) | 3 min (first run 15 min) |
| Frontend lint + type + tests | 2 min |
| npm audit | 30 sec |
| Frontend SAST (semgrep) | 1–2 min |
| Playwright | 3–5 min |
| Sonar scan + quality gate wait | 2 min |
| **Total** | **~15 min** typical |

A failed gate fast-fails the pipeline so feedback to developers is < 5 minutes for most issues.
