# Local penetration test — Covenexa

**Date:** 5 November 2026 · **Target:** the local Docker stack through the gateway (`http://localhost:8080`),
with real Keycloak, SQL Server 2022 and the WireMock bank stubs. **Run by:** the build team (self-test).

> This is a **self-assessment on the local stack**, not the independent penetration test and ASVS L2
> review the PRD requires before go-live. Those must be done by an outside team against a production-like
> environment with the bank's written authorization. This test attacked only this machine; no real system
> was touched and nothing left the host.

## Method

A read-mostly probe script drives real Keycloak tokens for `rm-anil`, `rm-priya`, `auditor-1` and `admin-1`
against the gateway, covering authentication and session handling, access control and IDOR, injection
(SQL, path traversal, the formula DSL), response headers, CORS and the Host header. It makes no destructive
write; the writes it attempts are expected to be refused, and the refusal is the test. Results below are
every check and its outcome. The script is kept out of the repository (session scratchpad).

## Result: 30 of 30 checks pass

| Area | Checks | Result |
|---|---|---|
| Authentication / session | 6 | Pass |
| Access control / IDOR / roles | 5 | Pass |
| Exposure (actuator) | 4 | Pass |
| SQL injection | 4 | Pass |
| Path traversal | 3 | Pass |
| Formula DSL | 1 | Pass |
| Headers / CSP / CORS / Host | 7 | Pass |

### Authentication and session

- No token → 401. Tampered JWT signature → 401. `alg=none` forgery → 401. Garbage bearer → 401.
- The dev-mode `X-User` header is ignored in JWT mode (→ 401); it cannot stand in for a token.
- A password-grant on an unknown client is refused by Keycloak, so no cross-client token could be minted
  to test audience from here. Issuer and audience checks are configured in Core (`application-prod.yml`,
  `COVENANT_JWT_ISSUER` / `COVENANT_JWT_AUDIENCE`); the independent test should confirm them against the
  real IdP.

### Access control and IDOR

- `rm-anil` cannot read `rm-priya`'s CP: the review grid, the CP details and the document list all return
  404 (the app does not reveal that the CP exists).
- `auditor-1`, a read-only role, cannot perform a covenant-status write (→ 404/403).
- Ops/support diagnostics reject a plain admin JWT (→ 401); they need the separate support token.

### Exposure

- `/actuator`, `/actuator/env`, `/actuator/health`, `/actuator/prometheus` through the public gateway all
  return the SPA `index.html` (HTTP 200, `text/html`, 459 bytes), **not** the actuator. The actuator is
  bound to the internal port 9320, which the gateway does not proxy. nginx serves the SPA for any path
  outside `/api` and `/dps`.

### Injection

- SQL metacharacters in a CP id and in the covenant-search `q` parameter return 401/404/200 with no SQL
  error text and no HTTP 500 — parameterised queries hold.
- Path traversal on the document-file route (`..%2F..%2F..`, `%2e%2e%2f…`, mixed case) leaks nothing:
  nginx normalises the `..` segments so the path falls outside `/dps` and reaches the SPA, or DPS answers
  404. `/etc/passwd`, `nginx.conf` and `win.ini` were never served.
- The formula DSL is a closed recursive-descent AST interpreter (`FormulaEvaluator.java`): arithmetic,
  comparison and boolean nodes only. No `Runtime.getRuntime`, `ProcessBuilder`, `ScriptEngine`,
  `Class.forName` or `GroovyShell` — there is no code-execution path, and no HTTP endpoint runs an
  arbitrary expression for an RM.

### Headers, CORS, Host

- `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, and a strict `Content-Security-Policy`
  (`default-src 'self'`, `object-src 'none'`, `frame-ancestors 'none'`) are present.
- A hostile `Origin` is not reflected and no `Access-Control-Allow-Credentials` is returned — CORS is not
  open.
- The `Server` banner is just `nginx`, with no version.
- **HSTS** is absent on the local plain-HTTP gateway (expected). The production TLS terminator (load
  balancer) must add `Strict-Transport-Security`. Informational.

## Flagged then cleared

The first run raised seven items; all seven were verified as false positives, not fixes:

- Four "actuator exposed" and one "path traversal" — all were the SPA `index.html` catch-all (200,
  `text/html`), confirmed by inspecting the body and `Content-Type`; no backend or file was reached.
- One "formula engine code-exec" — the string `Runtime` was a comment word ("Runtime problems"), not
  `Runtime.getRuntime`.
- One "HSTS missing" — expected on a local HTTP listener; it belongs on the production TLS terminator.

## Dependency and image scan

| Scan | Tool | Result |
|---|---|---|
| DPS Python static analysis | bandit | No issues (3,323 LOC, 0 `#nosec`) |
| UI dependencies | `npm audit --omit=dev` | 0 vulnerabilities |
| CI static analysis | bandit + semgrep (`security.yml`) | 0 findings (from the suite) |
| Python dependency CVEs | pip-audit | **Not run — not installed.** Add `pip-audit` (or `safety`) to CI |
| Container image CVEs | trivy | **Not run — not installed.** Scan the built images before release |

## Limits of this test

- Local only: the real gateway/WAF, Entra ID, and the bank's CP, loan, notification and workflow services
  are not present, so their auth and access paths are untested.
- Authenticated, grey-box, by the team that wrote the code — not independent.
- No fuzzing, no business-logic abuse campaign, no image/dependency CVE scan (tooling absent).

## Conclusion

On the local stack, the application stands up to the common web attacks: authentication cannot be forged
or bypassed, one RM cannot reach another's data, roles are enforced, SQL injection and path traversal are
blocked, the formula language cannot execute code, and the security headers and CORS policy are correct.
This clears the cheap findings early. It does **not** replace the independent penetration test, the ASVS L2
review, or a container/dependency CVE scan, all of which remain required before full production.
