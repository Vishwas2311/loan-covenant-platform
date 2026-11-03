# Local real testing

Everything needed to test the Covenant Platform on one computer the way it will run for real users:
real sign-in with usernames and passwords, roles, tokens between the services, AI answers from Azure
OpenAI, and the bank's systems replaced by local stand-ins.

| Real system | Local stand-in |
|---|---|
| Microsoft Entra ID (sign-in) | **Keycloak** in Docker, `http://localhost:8180` |
| The bank's services (credit proposals, attributes, data API, workflow, notifications, file store) | **WireMock** stubs, `http://localhost:9311` |
| Production SQL Server | SQL Server in Docker |
| Hosting | Docker Compose on this computer |
| Azure OpenAI | Azure OpenAI itself (your key in `deploy/.env`) |

## 1. What you need

- Docker Desktop with at least 12 GB of memory for containers (Settings → Resources).
- Python 3.12 (for the test scripts).
- Free ports: 8080, 8180, 9310, 9311, 9320, 9400, 1433.

## 2. One-time setup

1. Create `deploy/.env` from the example:
   ```bash
   cp deploy/.env.example deploy/.env
   ```
2. Replace the first three values (`MSSQL_SA_PASSWORD`, `COVENANT_SUPPORT_TOKEN`, `DEMO_READER_PASSWORD`) with your own. The SQL Server password needs upper and lower case, a digit and a symbol.
3. Generate the Keycloak passwords and client secrets (writes random values into `deploy/.env`; prints nothing):
   ```bash
   python -c "import re,secrets;p='deploy/.env';s=open(p).read();[s:=re.sub(rf'^{k}=.*$',f'{k}={secrets.token_urlsafe(24)}',s,flags=re.M) for k in ('KC_ADMIN_PASSWORD','KC_DEMO_PASSWORD','CORE_SERVICE_CLIENT_SECRET','DPS_SERVICE_CLIENT_SECRET')];open(p,'w').write(s)"
   ```
4. For the AI features, fill in `AZURE_API_KEY`, `AZURE_API_BASE`, `AZURE_DEPLOYMENT` and `AZURE_API_VERSION` from the Azure portal (Azure OpenAI resource → Keys and Endpoint; the deployment name from Azure AI Foundry). With the key empty, the AI features answer from rules instead.

`deploy/.env` is git-ignored. Never commit it, paste it anywhere, or put its values in a ticket.

## 3. Start

```bash
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.real.yml --env-file deploy/.env up -d --build
```

The first build takes 10–20 minutes (the heavy document worker image is about 4 GB). Later starts take about a minute. Wait until Core is ready:

```bash
curl -s http://localhost:9310/readyz
```

It prints `{"status":"UP"}`.

To go back to the quick development mode (no passwords, "Act as" picker instead of sign-in), start without the second file:

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d
```

## 4. Sign in

Open **http://localhost:8080**. You are sent to the Keycloak sign-in page.

Every test user has the same password: the `KC_DEMO_PASSWORD` value in `deploy/.env`.

| User | Role | Sees |
|---|---|---|
| `rm-anil` | RM | CP-1001 … CP-1007, CP-2001 … CP-2005 |
| `wrm-meera` | Approver | the same CPs as `rm-anil` |
| `rm-priya` | RM | the other demo CPs |
| `wrm-vikram` | Approver | the same CPs as `rm-priya` |
| `rm-load` | RM | the load-test CPs `LT-…` (after `python loadtest/seed.py`) |
| `author-1` | Author | the covenant library (create and edit covenants) |
| `admin-1` | Admin | everything, plus settings |
| `auditor-1` | Auditor | everything, read-only |
| `support-1` | Support | everything, plus document jobs and the support toolkit |

The header shows who you are and your roles; **Sign out** ends the Keycloak session.

Keycloak's admin console is at http://localhost:8180 (user `admin`, password `KC_ADMIN_PASSWORD`). Use it to add users, change roles or reset a password. Changes there last until the Keycloak container is recreated; to make one permanent, edit `deploy/keycloak/covenant-realm.json`.

## 5. Automated check (2 minutes)

```bash
python deploy/smoke_real.py
```

It signs in as several users and checks 12 things: token contents; Core and DPS refusing requests without a token; an RM seeing their own CP but not another RM's; role rules; DPS passing the user's token on to Core; Core calling DPS with its own client-credentials token; and Ask AI answering from Azure OpenAI. It ends with `All checks passed`.

## 6. Manual test script

Do these in order; each step says what you should see.

| # | Sign in as | Do | Expect |
|---|---|---|---|
| 1 | `rm-anil` | Open **Portfolio** | The CP table with covenant counts per status |
| 2 | `rm-anil` | Open CP-1008 in the address bar: `http://localhost:8080/cp/CP-1008` | Not found: it belongs to `rm-priya` |
| 3 | `rm-anil` | **Documents** → upload a PDF agreement from `golden-set/documents/` to CP-1003 as *Loan agreement* | The job finishes within a minute; extracted terms show with page references |
| 4 | `rm-anil` | **Onboarding** → CP-1003 | The extracted covenants appear as recommendations with confidence and a quote; apply one |
| 5 | `rm-anil` | **Documents** → upload a financial statement (CSV or Excel) to CP-1001 with a period end date | Values appear with their cell references; Core re-assesses the covenants that use them |
| 6 | `rm-anil` | **CP workspace** → CP-1001 → a breached covenant → request a waiver | Status *Waiver requested* |
| 7 | `rm-anil` | Look for an *Approve* button on that request | None: an RM cannot decide (Core also refuses the call with 403) |
| 8 | `wrm-meera` | Same covenant → approve | Status *Waiver approved*; the status history shows both steps |
| 9 | `rm-anil` | **Reports** → open breaches → download Excel and PDF | Both files open |
| 10 | `rm-anil` | **Reports** → **Ask AI**: "Which of my covenants are breached?"; then **Documents** → the statement from step 5 | An answer written by Azure OpenAI about your CPs only; the statement shows an AI-written narrative |
| 11 | `author-1` | **Covenant library** → create a covenant | Saved; `rm-anil` cannot do this |
| 12 | `support-1` | **Document jobs** | All DPS jobs with their status; a failed one can be retried |
| 13 | `admin-1` | **Settings** → change a setting | Saved; other roles do not see Settings |
| 14 | any | **Sign out** | Back at the Keycloak sign-in page |

**The daily breach run** normally fires at 06:00. To run it now (as `admin-1`):

```bash
python -c "import json,urllib.request as u,urllib.parse as p;env=dict(l.split('=',1) for l in open('deploy/.env').read().splitlines() if '=' in l and not l.startswith('#'));t=json.load(u.urlopen(u.Request('http://localhost:8180/realms/covenant/protocol/openid-connect/token',data=p.urlencode({'grant_type':'password','client_id':'covenant-cli','username':'admin-1','password':env['KC_DEMO_PASSWORD'].strip()}).encode())))['access_token'];print(u.urlopen(u.Request('http://localhost:8080/api/v1/monitoring/notification-runs',method='POST',headers={'Authorization':'Bearer '+t})).read().decode())"
```

**The breach emails** go to the bank's notification service, which WireMock stands in for. See what was sent at http://localhost:9311/__admin/requests (search for `/api/v1/notifications`).

## 7. Load test (optional)

```bash
python loadtest/seed.py --cps 700
```

```bash
python loadtest/load.py --users 50 --seconds 60
```

```bash
python loadtest/uploads.py --count 50
```

These run against the development mode (start without `docker-compose.real.yml`). Results and what they mean: `loadtest/README.md`.

## 8. Where things are

| Address | What |
|---|---|
| http://localhost:8080 | The app |
| http://localhost:8180 | Keycloak (sign-in, admin console) |
| http://localhost:9310/swagger-ui.html | Core API documentation |
| http://localhost:9320/actuator/prometheus | Core metrics (internal port, no token) |
| http://localhost:9400/docs | DPS API documentation |
| http://localhost:9311/__admin/requests | Every call made to the bank-service stand-ins |
| `localhost,1433` (user `sa`) | SQL Server: databases `covenant` and DPS's tables |

Logs: `docker logs -f covenant-platform-core-1` (also `-dps-api-1`, `-dps-worker-1`, `-keycloak-1`, `-ui-1`).

## 9. Stop and reset

Stop, keeping all data:

```bash
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.real.yml --env-file deploy/.env down
```

Stop and delete all data (databases, uploaded documents, AI model cache), for a clean start:

```bash
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.real.yml --env-file deploy/.env down -v
```

## 10. Moving from local to real

The application code does not change; only settings do. `docs/runbook.md` has the full list.

| Local | Production |
|---|---|
| Keycloak realm `covenant` | Entra ID: one API registration shared by Core and DPS, the UI as a single-page app, one client application each for Core and DPS with the `SERVICE` role |
| `OIDC_AUTHORITY=http://localhost:8180/realms/covenant` (UI) | `https://login.microsoftonline.com/<tenant>/v2.0` |
| `COVENANT_JWT_ISSUER`, `DPS_JWT_ISSUER` = the Keycloak realm URL | `https://login.microsoftonline.com/<tenant>/v2.0` |
| audience `covenant-platform` | the shared API's app ID URI |
| token URL `…/realms/covenant/protocol/openid-connect/token` | `https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token` |
| scope `openid` (service clients) | `api://<shared-api>/.default` |
| WireMock base URLs | the bank's real service URLs |
| `SPRING_PROFILES_ACTIVE=local-stack` | `prod`, plus `DPS_ENV=prod` |
| Docker Compose | the chosen hosting platform (decision D9) |

## 11. Troubleshooting

| Symptom | Fix |
|---|---|
| `docker compose` says `run the secret step` | Step 2.3 was skipped: generate the Keycloak values |
| The app shows "Act as" instead of sign-in | It was started without `docker-compose.real.yml`; start with both files |
| Sign-in page says *Invalid username or password* | Use the `KC_DEMO_PASSWORD` value; it changes if you rerun step 2.3 and recreate Keycloak |
| You changed `KC_DEMO_PASSWORD` but the old one still works | Keycloak imports users only on its first start: `docker compose ... rm -sf keycloak` then start again |
| Pages fail with 401 after sign-in | Check the Core log for `JWT`; the issuer must be exactly `http://localhost:8180/realms/covenant` |
| Ask AI answers but says `method: RULES` | `AZURE_API_KEY` is empty or wrong; check `docker logs covenant-platform-dps-api-1` |
| Ask AI fails with `temperature` | Leave `DPS_AI_TEMPERATURE` unset: newer models allow only their default |
| Port already in use | Stop the other program, or change the left-hand port in the compose file |

## 12. Safety

- Keycloak here is for testing only: it runs in development mode, over plain HTTP, with simple test users.
- The `covenant-cli` client allows signing in with a password from a script; it exists only for the local test scripts and must not exist in production.
- Keys and passwords live only in `deploy/.env`. If one is ever shared (in chat, email or a ticket), create a new one (for Azure: Keys and Endpoint → *Regenerate*) and update `deploy/.env`.
