# Covenant Platform UI

One UI for Covenant Core and the Document Processing Service. React 18, TypeScript, Mantine 8, TanStack Query, React Router.

| Screen | What it does | Backed by |
|---|---|---|
| Portfolio | Scorecard per CP: monitored, breached, errors, awaiting review | Core `/api/v1/monitoring/summary` |
| CP workspace | Covenant grid with actual, headroom, result; waiver/extension **requests** and approver **decisions** (maker-checker); status history; period comparison with Excel; certificate requirements per period; manual values (approvers) | Core monitoring, DPS manual values |
| Onboarding | One grid of master, ML and loan-agreement (EXTRACTED) proposals with the page quote; apply with the right threshold source | Core onboarding (C1 merged in Core) |
| Documents | Upload agreements/statements/evidence for one or more CPs; job status; review extracted terms (approve, edit, reject); statement values and advisory narrative | DPS |
| Covenant library | Search, page, Excel export | Core |
| Reports | Breach rate by segment and sector; open breaches with escalation flag; Excel management report | Core analytics and reports |
| Settings (ADMIN) | Fiscal year, schedule windows, maker-checker switch, reporting currency, FX rates | Core settings and FX |

## Identity

Production sends an Entra ID access token (`setAccessToken`) as `Authorization: Bearer`. In development the header switcher ("Act as") sends `X-User` / `X-Roles`, which Core and DPS accept only in their `DEV_HEADER` mode.

## Run

```bash
npm install
npm run dev            # http://localhost:9312, proxies /api to Core (9310) and /dps to DPS (9400)
```

## Test and build

```bash
npm test               # vitest
npm run test:coverage  # thresholds: lines 80, branches 70, functions 70
npm run build          # type-check + production bundle
```
