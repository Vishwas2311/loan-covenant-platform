# Security

## Reporting a vulnerability

Please do not open a public issue. Use GitHub's private reporting instead: **Security → Report a vulnerability** on this repository. We aim to reply within 5 working days.

## Production settings

Both services refuse to start with an unsafe configuration, so a missing setting shows up at deploy time rather than as an open door.

| Setting | Service | Why |
|---|---|---|
| `SPRING_PROFILES_ACTIVE=prod` | Core | Turns on JWT sign-in, plaintext-password and H2 refusal, and Quartz clustering. Core will not start in `DEV_HEADER` mode with this profile. |
| `COVENANT_JWT_ISSUER`, `COVENANT_JWT_AUDIENCE` | Core | Required in JWT mode. Entra ID signs every tenant's tokens with the same keys, so without these a token issued for any other tenant or app would be accepted. |
| `DPS_ENV=prod` | DPS | DPS will not start in `DEV_HEADER` mode with this set. |
| `DPS_JWT_ISSUER`, `DPS_JWT_AUDIENCE` | DPS | Required in JWT mode, for the same reason as Core. |
| `COVENANT_DATA_SOURCES_ALLOWED_REST_BASE_URLS` | Core | The only URL prefixes a REST data source may call (comma list). Empty in production by default, so every REST source with its own `baseUrl` is refused until its host is listed. |
| `COVENANT_DPS_TOKEN_URI`, `_CLIENT_ID`, `_CLIENT_SECRET`, `_SCOPE` and `DPS_CORE_TOKEN_URL`, `_CLIENT_ID`, `_CLIENT_SECRET`, `_SCOPE` | Core, DPS | Service-to-service calls use OAuth client credentials. Each service fetches its own token and refreshes it before it expires, so no long-lived token sits in configuration. |
| `COVENANT_DATA_SOURCES_ALLOWED_JDBC_HOSTS` | Core | The only SQL Server hosts an MSSQL data source may connect to (the URL's host, `serverName` and `failoverPartner` are all checked). Empty in production by default. |
| `COVENANT_MANAGEMENT_PORT` | Core | The actuator (metrics, Prometheus) listens here without a token. Keep the port internal. |
| `COVENANT_DS_*` | Core | Passwords of MSSQL data sources, filled from the secret store and named by each source's `secretRef`. Plaintext passwords are refused in production. |

## What the code enforces

- **Sign-in and roles.** Entra ID JWT in Core and DPS, with issuer and audience checks. `DEV_HEADER` ("Act as") is for local use only.
- **Per-CP access.** Every CP endpoint goes through `CpAccessGuard` in Core. DPS asks Core whether the caller may access a CP, and Ask AI reads Core data as the caller.
- **Data sources.** MSSQL sources accept only `jdbc:sqlserver://` URLs to allowlisted hosts (H2 without `INIT` scripts only in dev and tests). REST sources may call only allowlisted hosts. A source with `auth: dps` always goes to the configured DPS URL, so the service credentials never reach another host.
- **Uploads.** File type allowlist, size limit, content-addressed storage that cannot escape its folder, and files served back with a fixed type and `nosniff`.
- **Browser.** The UI is served with a same-origin Content-Security-Policy, `X-Frame-Options: DENY` and `nosniff`. The access token is held in memory, never in browser storage.

## Repository

Dependabot alerts and security updates, secret scanning with push protection, and required CI checks on `master` are turned on.
