# 08: Decisions Log

Answers given by the product side on 2026-11-02. They are folded into [02](02-target-architecture.md), [05](05-prd.md) and [06](06-implementation-plan.md).

| # | Decision | Answer | Consequence |
|---|---|---|---|
| D1 | UI base | **Brand-new UI** | New SPA for the combined product (replaces both UIs). Their tested components are reused where they fit (formula builder, frequency picker, disposition modal, threshold editor) |
| D1a | New UI technology | Delegated → **React 18 + TypeScript + Mantine 8 + TanStack Query/Table + Vite** | Same stack as their app, so their components, API clients and Vitest/Playwright setup are reused; new layout, navigation (URL routing) and design |
| D2 | Financial data source | **Uploads + bank systems** | DPS Flow B (statements) **and** Core's REST/MSSQL bank sources, both through Core's data-source mechanism |
| D3 | Our extra features | Delegated → see table below | |
| D4 | Maker-checker | **Configurable, default ON** | Waiver/extension requests need APPROVER approval; admin setting to switch off |
| D5 | Identity source | **Bank CP service** | cpId / obligorId / RM / WRM from CP service; our BRW-n/FAC-n IDs retired |
| D6 | Currency and units | **Unit per measure + reporting-currency setting** | Values stored in source unit; display converted to reporting currency (FX rates table, dated) |
| D7 | One agreement → several facilities/CPs | **Yes, support it** | A document links to N CPs; Flow A proposals offered on each CP's onboarding |
| D8 | Compliance certificate | **Per CP and period** | Certificate requirement per CP per reporting period; missing → PENDING / OVERDUE by due date |
| D9 | Manual entry of missing values | **Yes: approver role, note, audit** | Stored as USER-sourced statement values in DPS, then normal assessment |
| D10 | Deployment | **Not decided** | Build container-based and cloud-neutral (Docker images, env config, SQL Server); decide target before go-live |
| D11 | LLM provider | Not asked → **Azure OpenAI (gpt-4.1)** kept as default behind an `LlmClient` interface | If deployment ends up on-prem without Azure access, swap provider via config |
| D12 | Codebase ownership | **We build both** | Fork their repo for Core changes; new repo for DPS; new repo (or folder) for the new UI |
| D13 | Golden set owner | **Open** | Needed before R1 release gate |
| D14 | Pilot scope | **Open** | Suggest 5–10 CPs, 2–3 RMs |

## D3: Extra features from our app (decided on merit)

| Feature | Keep? | When | Why / how |
|---|---|---|---|
| AI risk narrative (summary + actions) | **Yes** | R1 | Useful to RMs; produced once per result in Flow B Report; labelled "AI-generated, not a decision" |
| Cycle comparison + Excel exports (cycle matrix, master covenants) | **Yes** | R1 | Period-by-period comparison by `asOfDate`; Excel is widely used by credit teams |
| Dashboard analytics (industry risk treemap, exposure by sector, entity mix) | **Yes** | R2 | Rebuilt on Core data (sector from CP service, amounts in reporting currency) |
| Trend direction per covenant | **Yes (advisory)** | R2 | Computed from assessment history in Core; shown as a trend arrow, not a status |
| Heuristic confidence score per metric | **No** | — | Not calibrated; misleading next to real results. Extraction confidence (Flow A) is kept |
| Ask AI chat | **Yes, rebuilt** | R3 | Answers from Core APIs with the user's portfolio scope and a small, cited context (today's sends about 98k chars per question) |
| Financial news trend | **Optional, off by default** | R3 | External source (Google News RSS); needs approval for outbound calls |
| Agent audit screens | **Replaced** | R1 | Becomes "Document jobs & AI runs" (steps, timings, tokens, cost) |
| Fake e-mail dialog, status dialog that doesn't save, dummy linked-facility toggle | **Drop** | — | Replaced by real notifications (Core), dispositions (Core) and D7 linked documents |
