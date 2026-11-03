# Frontend Developer Guide — Covenant Monitoring System

> **Audience:** A React / TypeScript developer joining the frontend team. This is a knowledge-transfer
> (KT) document: read it once end-to-end, then use it as a reference. It covers the stack, folder
> layout, the four architectural layers (`api → types → hooks → pages/components`), the navigation
> model, data-fetching and mutation conventions, forms & the formula builder, file uploads, and how
> to build/test/lint locally.

For the product overview see the root [`README.md`](../README.md); for the backend API that this UI
consumes see the [backend developer guide](backend-developer-guide.md).

---

## Table of contents

1. [What this app is](#1-what-this-app-is)
2. [Technology stack](#2-technology-stack)
3. [Get it running in 5 minutes](#3-get-it-running-in-5-minutes)
4. [Folder layout & the four layers](#4-folder-layout--the-four-layers)
5. [Bootstrapping: `main.tsx` & providers](#5-bootstrapping-maintsx--providers)
6. [Navigation model (`App.tsx`)](#6-navigation-model-apptsx)
7. [The API layer (`api/`)](#7-the-api-layer-api)
8. [Data fetching & mutations (`hooks/` + React Query)](#8-data-fetching--mutations-hooks--react-query)
9. [Pages & the feature map](#9-pages--the-feature-map)
10. [Shared components](#10-shared-components)
11. [Forms, formula builder & file uploads](#11-forms-formula-builder--file-uploads)
12. [Testing, linting & quality gates](#12-testing-linting--quality-gates)
13. [How to add a feature (recipes)](#13-how-to-add-a-feature-recipes)
14. [Conventions to memorise](#14-conventions-to-memorise)

---

## 1. What this app is

A single-page React app that is the UI for the whole covenant lifecycle: authoring covenant
templates & measures, building formula conditions, registering data sources, onboarding Credit
Proposals (CPs), monitoring compliance, and — the newest area — the **RM assessment workspace** where
an RM reviews a CP's covenants, records waiver/extension **dispositions**, attaches evidence, and
pulls **AI status suggestions**.

It talks to the backend over a small REST surface (all paths under `/api/v1/...`). In dev, Vite
proxies `/api` to the backend on port `9310`, so there is no CORS config and no base-URL juggling.

---

## 2. Technology stack

| Concern | Choice | Notes |
|---|---|---|
| Language | TypeScript 5.6 (strict) | `tsc --noEmit` is part of `build` |
| UI library | **React 18.3** | function components + hooks only |
| Component kit | **Mantine v8** | `@mantine/core`, `/dates`, `/form`, `/hooks`, `/notifications` |
| Tables | `mantine-react-table` (MRT) + `@tanstack/react-table` | data grids |
| Data fetching | **TanStack React Query v5** | server-state cache; **no Redux** |
| Icons | `@tabler/icons-react` | |
| Dates | `dayjs` | |
| Build tool | **Vite 5** | dev server on port `9312`, `@` alias → `src/` |
| Unit tests | **Vitest 2** + Testing Library + jsdom | `*.test.tsx` beside the source |
| E2E | **Playwright** | `tests/e2e/**` |
| Lint | ESLint 8 (`--max-warnings=0`) + a security config (`sast`) | + `audit-ci` for deps |

> Note: `@tanstack/react-router` is a dependency but the app currently uses a **hand-rolled
> discriminated-union view state** in `App.tsx` (see [§6](#6-navigation-model-apptsx)), not the
> router. Don't assume file-based routing.

---

## 3. Get it running in 5 minutes

Node lives at an absolute path in this workspace (nothing is on `PATH`). From `frontend/`:

```bash
npm install            # first time only
npm run dev            # Vite dev server on http://localhost:9312 (proxies /api → :9310)
```

Start the **backend on 9310 first** (see the backend guide) or the `/api` calls 502. Then:

```bash
npm run type-check     # tsc --noEmit
npm run test           # vitest run (unit)
npm run lint           # eslint, zero warnings allowed
npm run sast           # security-focused eslint config
npm run build          # tsc --noEmit && vite build
npm run test:e2e       # Playwright (needs the app running)
```

The root `build.bat` / `start.bat` / `stop.bat` orchestrate backend + frontend together.

---

## 4. Folder layout & the four layers

```
frontend/src/
├── main.tsx            # entry: mounts providers (Mantine, React Query, Notifications) + <App/>
├── App.tsx             # AppShell + navigation (view-state switch), renders the active page
├── theme/index.ts      # Mantine theme (coverage-excluded)
│
├── api/                # LAYER 1 — thin fetch wrappers, one module per resource
│   ├── client.ts       #   the shared apiFetch/api helper (GET/POST/PUT/PATCH/DELETE + errors)
│   ├── covenants.ts  dataSources.ts  measures.ts  formulas.ts
│   ├── onboarding.ts reference.ts    settings.ts
│
├── types/              # LAYER 2 — TypeScript types mirroring the backend DTOs (coverage-excluded)
│   ├── covenant.ts dataSource.ts masterData.ts measure.ts onboarding.ts settings.ts
│
├── hooks/              # LAYER 3 — React Query hooks (queries + mutations) (coverage-excluded)
│   ├── useCovenants.ts useDataSources.ts useMasterData.ts useMeasures.ts useSettings.ts
│
├── pages/              # LAYER 4a — one folder per feature area; page = a screen
│   ├── covenant-master/  measures/  formula/  data-sources/
│   ├── onboarding/       settings/  monitoring/
│
└── components/         # LAYER 4b — reusable presentational/logic components
    ├── DataTable.tsx FormulaBuilder.tsx FormulaText.tsx FrequencyPicker.tsx
    ├── MultiSelectAll.tsx ResultBadge.tsx ThresholdEditor.tsx
    ├── dispositionStatus.tsx monitoringTiming.tsx
    └── formula/        # formula-builder sub-components
```

### The dependency direction (never violate it)

```
pages / components   →   hooks   →   api   →   backend HTTP
        │                  │          │
        └──────────────────┴──────────┴──→   types (shared, imported everywhere)
```

- **`api/`** knows URLs and HTTP verbs, nothing about React.
- **`types/`** are pure interfaces mirroring backend DTOs — no logic.
- **`hooks/`** wrap `api/` calls in `useQuery`/`useMutation` and own query keys + cache invalidation.
- **`pages/`** compose hooks + components into screens; **`components/`** are reusable and mostly
  stateless (state comes in via props/hooks).

`hooks/**` and `types/**` are **excluded from coverage** (see [§12](#12-testing-linting--quality-gates)),
so keep real logic out of them — put testable logic in `api/`, `components/`, and `pages/`.

---

## 5. Bootstrapping: `main.tsx` & providers

`main.tsx` mounts, in order, the providers every screen relies on:

- **`MantineProvider`** (with the custom `theme`, light color scheme) + Mantine CSS imports. Order
  matters: `mantine-react-table/styles.css` is imported **after** Mantine's so table chrome layers
  correctly.
- **`Notifications`** (top-right) — call `notifications.show({...})` anywhere for toasts.
- **`QueryClientProvider`** with defaults: `staleTime: 30s`, `retry: 1`,
  `refetchOnWindowFocus: false`. These defaults are why lists feel snappy and don't refetch on tab
  focus — override per-query only when you need to.
- `<StrictMode>` wraps everything (expect double-invoked effects in dev).

---

## 6. Navigation model (`App.tsx`)

There is **no router**. `App.tsx` holds a single `useState<View>` where `View` is a **discriminated
union** — each screen is a variant with its own params:

```ts
type View =
  | { kind: 'covenants' }
  | { kind: 'covenant-detail'; id: number }
  | { kind: 'covenant-form'; id: number | null }   // null = create, number = edit
  | { kind: 'measures' } | { kind: 'measure-form'; id: number | null }
  | { kind: 'formula-builder' }
  | { kind: 'data-sources' } | { kind: 'data-source-form'; id: number | null }
  | { kind: 'onboarding' }
  | { kind: 'monitoring' }
  | { kind: 'settings' }
  | { kind: 'cp-detail'; cpId: string }
  | { kind: 'cp-assessment'; cpId: string }         // RM assessment grid
  | { kind: 'cp-history'; appliedId: number; cpId: string; covenantName: string }
  | { kind: 'assessment-detail'; id: number; origin: View };  // origin = where to go "back"
```

- A `<AppShell>` renders the left `NavLink` list (`NAV_ITEMS`) and a `switch (view.kind)` in the body
  renders the active page, passing a `setView`-style navigation callback down so a page can push the
  next view (e.g. clicking a CP row sets `{ kind: 'cp-assessment', cpId }`).
- `isNavRoot(kind)` decides which top-level nav item is highlighted for detail/sub views (e.g.
  `cp-assessment` keeps *Monitoring* active).
- `assessment-detail` carries an `origin: View` so "back" returns to wherever you came from.

**To add a screen:** add a variant to `View`, a `case` in the render switch, and (if it's a top-level
destination) an entry in `NAV_ITEMS`.

---

## 7. The API layer (`api/`)

`api/client.ts` is the one place that touches `fetch`. Everything else calls the `api` helper:

```ts
export const api = {
  get, post, put, patch, delete   // each returns Promise<T>
};
```

Key behaviours built in — you get these for free, don't reimplement them:

- **JSON by default:** pass a JS object as the `json` arg and it's `JSON.stringify`'d with the right
  `Content-Type`.
- **Multipart aware:** if the body is a `FormData`, it does **not** set `Content-Type`, so the browser
  supplies the multipart boundary (critical for evidence/certificate uploads — see [§11](#11-forms-formula-builder--file-uploads)).
- **Typed errors:** a non-2xx response throws `ApiClientError` with `status`, `code`, `details`,
  parsed from the backend's shared `ErrorResponse` schema. Catch it in mutations to show a friendly
  `notifications.show({ color: 'red', ... })`.
- **204 / non-JSON:** returns `undefined` for 204, text for non-JSON responses.

Per-resource modules (e.g. `api/covenants.ts`) expose a plain object of functions that build the
`/api/v1/...` path and delegate to `api`. Query strings are built with `URLSearchParams`, dropping
`undefined`/empty params. **Keep these modules React-free.**

---

## 8. Data fetching & mutations (`hooks/` + React Query)

Hooks are the React Query boundary. The conventions (from `useCovenants.ts`) are consistent across
the app — follow them exactly:

- **A single `KEY` const per resource** (e.g. `const KEY = 'covenants'`), and structured query keys:
  `[KEY, 'search', params]`, `[KEY, 'one', id]`.
- **Queries:** `useQuery({ queryKey, queryFn, ... })`. Use `placeholderData: keepPreviousData` for
  paginated/searchable lists (no flicker between pages) and `enabled: id !== null` to defer a
  detail fetch until an id exists.
- **Mutations:** `useMutation({ mutationFn, onSuccess: () => client.invalidateQueries({ queryKey: [KEY] }) })`.
  Invalidating the **whole resource key** after a write is the standard refresh mechanism — the list
  and detail re-fetch automatically.
- For the monitoring/RM area, invalidate the CP-scoped key (e.g. `['monitoring','cp',cpId]`) after a
  disposition save/upload so the grid refreshes.

> Because `hooks/**` is coverage-excluded, hooks should stay thin (wire query key + api call +
> invalidation). Any non-trivial transformation belongs in `api/` or the page/component that is
> tested.

---

## 9. Pages & the feature map

| Folder | Pages | Feature |
|---|---|---|
| `covenant-master/` | `CovenantLibraryPage`, `CovenantDetailPage`, `CovenantFormPage` | browse / view / author covenant templates |
| `measures/` | `MeasureLibraryPage`, `MeasureFormPage` | manage the financial measures used in formulas |
| `formula/` | `FormulaBuilderPage` | build & live-validate a formula condition |
| `data-sources/` | `DataSourceListPage`, `DataSourceFormPage` | register external data feeds (columnar / key-value) |
| `onboarding/` | `CpOnboardingWizard` | onboard a CP: fetch attributes → recommend → apply covenants → ML thresholds |
| `settings/` | `ReportingSettingsPage` | reporting configuration |
| `monitoring/` | `MonitoringWorkspace`, `MonitoringDashboard`, `MonitoringSchedulePage`, `CpDetailPage`, `CpAssessmentPage`, `AssessmentDetailPage`, `AssessmentHistoryPage`, `DispositionModal`, `AiSuggestionModal`, `attachments` | the whole monitoring + RM assessment area |

### The RM assessment area (newest, worth understanding)

- **`MonitoringWorkspace`** — the landing screen after a breach email. A `SegmentedControl` toggles
  **By CP** (the US-MON-14 summary table) vs **By Covenant** (the US-MON-09 agenda/calendar).
  Selecting a CP opens `CpAssessmentPage`. The choice persists for the session.
- **`CpAssessmentPage`** — a grid of every covenant on the CP: **name · expression · actual value ·
  headroom · status · start/next/last dates · attachments**. Only **Status** and **Attachments** are
  editable. Rules baked into the UI:
  - `Complied` (result COMPLIANT/NA) rows are **locked**.
  - `Breached` rows may be set to **Waiver** or **Extension** only, via `DispositionModal`.
  - **Extension requires a date** (mandatory `DateInput`) before save.
  - NON_FINANCIAL covenants **hide Actual + Headroom**.
- **`AiSuggestionModal`** — calls the backend AI endpoint, shows a recommended status per covenant;
  the RM picks which to apply (override) or dismisses. Applying audits with source `AI`. On AI
  failure it shows a graceful "AI unavailable" message — no crash, no data change.
- **`attachments.tsx`** — the per-row evidence cell (and CP-level compliance-certificate panel)
  using Mantine's `FileButton`/`FileInput`.

---

## 10. Shared components

| Component | Purpose |
|---|---|
| `DataTable` | thin wrapper over MRT with the app's default table config |
| `FormulaBuilder` (+ `components/formula/`) | interactive formula editor; validates against the backend formula endpoints |
| `FormulaText` | renders a formula expression as readable text (uses backend render output) |
| `ThresholdEditor` | edit the numeric threshold(s) of a covenant condition |
| `FrequencyPicker` | choose a monitoring `Frequency` |
| `MultiSelectAll` | multi-select with a select-all affordance |
| `ResultBadge` | colored badge for an `AssessmentResult` |
| `dispositionStatus` | maps a disposition status → label/color (Complied/Breached/Waiver/Extension) |
| `monitoringTiming` | timing chips (overdue / due-soon / upcoming) from schedule dates |

These are the building blocks pages compose. Reuse them instead of re-styling badges/chips inline.

---

## 11. Forms, formula builder & file uploads

- **Forms:** use **`@mantine/form`** (`useForm`) for validation + state. Field-level errors from the
  backend arrive in `ApiClientError.details` — map them onto the form when a mutation rejects.
- **Formula building:** `FormulaBuilder` calls the backend formula endpoints
  (`api/formulas.ts` → `/api/v1/formula/*`) to validate, list measures/functions, and compute
  thresholds live, so an authored condition is verified before it's saved. Don't parse formulas on
  the client — the backend engine is authoritative.
- **File uploads:** build a `FormData`, append the file(s), and POST it via `api.post(path, formData)`
  — the client layer detects `FormData` and lets the browser set the multipart boundary. The upload
  goes **through our backend**, which forwards to the external file-upload service and returns a
  `fileId`. Do basic type/size checks client-side for a friendly message, but the backend validates
  authoritatively.

---

## 12. Testing, linting & quality gates

Tests live **beside the source** as `*.test.tsx` / `*.test.ts` and use Vitest + Testing Library
(`render`, `screen`, `userEvent`) in a jsdom environment (`src/test-setup.ts`).

**Coverage thresholds (Vitest, from `vite.config.ts`):**

| Metric | Threshold |
|---|---|
| Statements | 95% |
| Branches | 85% |
| Functions | 75% |
| Lines | 95% |

**Excluded from coverage:** `main.tsx`, `test-setup.ts`, `types/**`, `hooks/**`, `theme/**`,
`**/index.ts`, `*.test.*`, config files. So the meaningful test targets are **`api/`, `components/`,
and `pages/`** — write tests there.

Testing notes baked into the config:
- `testTimeout`/`hookTimeout` are **15s** (Mantine + `userEvent` component tests are legitimately
  slow under the parallel suite). Don't lower them.
- Mock the network at the `api` layer (or with `fetch` mocks), not by hitting a real backend.

**Other gates:**
- `npm run lint` — ESLint with **zero warnings allowed** (`--max-warnings=0`), incl. `react-hooks`,
  `jsx-a11y`.
- `npm run sast` — the security ESLint config (`eslint-plugin-security`).
- `npm run build` — type-check (`tsc --noEmit`) then Vite build; must pass with no TS errors.
- `audit:ci` — dependency vulnerability gate.

---

## 13. How to add a feature (recipes)

**Add a new screen backed by a new backend resource:**
1. `types/<resource>.ts` — mirror the backend request/response DTOs.
2. `api/<resource>.ts` — a plain object of functions building `/api/v1/...` paths via `api`.
3. `hooks/use<Resource>.ts` — `useQuery`/`useMutation` with a `KEY` const and `invalidateQueries`.
4. `pages/<area>/<Screen>Page.tsx` — compose hooks + shared components; handle loading/error/empty.
5. `App.tsx` — add a `View` variant, a render `case`, and (if top-level) a `NAV_ITEMS` entry.
6. Tests: `<Screen>Page.test.tsx` + `api/<resource>.test.ts` (remember coverage lives in api/pages).

**Add a field to an existing form:** update the `types/` interface, the `api/` payload, the
`@mantine/form` `useForm` initial values + validation, and the JSX — then extend the page test.

**Add a table column:** add it to the MRT column defs in the page; if it needs a badge/chip, reuse or
extend a `components/` helper rather than styling inline.

---

## 14. Conventions to memorise

- **Layer direction is one-way:** `pages/components → hooks → api → backend`; `types` are shared.
  Never call `fetch`/`api` directly from a component — go through a hook.
- **Query keys are structured and per-resource;** invalidate the resource key on write to refresh.
- **All backend paths are `/api/v1/...`;** Vite proxies `/api` → `:9310` in dev (no CORS, no base URL).
- **Errors are `ApiClientError`** with `status`/`code`/`details` — surface them via
  `notifications.show`.
- **Uploads use `FormData`** and rely on the client layer to omit `Content-Type`.
- **Keep logic out of `hooks/` and `types/`** (coverage-excluded); put testable code in
  `api/`/`components/`/`pages/`.
- **Don't parse formulas client-side** — the backend formula engine is authoritative; use
  `FormulaBuilder`/`FormulaText`.
- **Zero ESLint warnings, strict TS, 95/85/75/95 coverage** — the build enforces all of these.
- **No Redux, no file-based router today** — server state is React Query; navigation is the `View`
  union in `App.tsx`.
```
