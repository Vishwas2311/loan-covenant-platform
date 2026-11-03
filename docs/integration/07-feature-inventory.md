# 07: Feature and Field Inventory, with Merge Decisions

How this was checked (2026-11-02):
- **Their app:** run locally (backend dev profile + Vite UI). Screens opened in the browser: Covenant Library, Covenant detail, Edit covenant, Measure Library, Create measure, Data Sources, Register data source (REST and MSSQL), CP Onboarding lookup. Every page component was then read for all labels and fields (28 pages and components).
- **Our app:** run locally; the HTML/JS in `web.py` was extracted for all views, fields and buttons; handlers were checked to see what actually saves.

Legend for **Decision**:
- **CORE** = keep their feature as is.
- **BRING** = carry our feature into the combined app.
- **MERGE** = combine both.
- **NEW** = neither app has it.
- **DROP** = remove.
- **ASK** = needed a decision. **All ASK items are now answered** in [08-open-decisions.md](08-open-decisions.md) (D1–D12); D13/D14 are still open.

## 1. Navigation (screens)

| Their screen | Our screen | Decision |
|---|---|---|
| Covenant Library (list, filter, view, create/edit) | Master Covenant (financial / non-financial lists, + Add Covenant, Download Excel) | **MERGE**: their library + our Excel download |
| Measure Library | — | CORE |
| Formula Builder | — | CORE |
| Data Sources (+ tables) | — | CORE (DPS registered here as a REST source) |
| CP Onboarding (Lookup → Review & Configure → Completed) | + Add Company (company form + loan upload + covenant master configuration) | **MERGE**: their wizard + our agreement upload and extraction (EXTRACTED rows) |
| Monitoring (By CP / By Covenant agenda + calendar) | Dashboard (treemap, exposure, entity pie, due soon, facility status) | **MERGE**: their monitoring + our analytics widgets (ASK D3) |
| CP detail / Assessment grid / Assessment detail / History / Cycle history | Company page: analysis report, cycle reports, compare cycles | **MERGE**: their grid as base + our report narrative, trend, compare cycles (ASK D3) |
| Reporting Settings | — | CORE |
| Production Support (diagnostics, health, audit, cleanup) | Agent Audits (agent registry, timeline, logs) | **MERGE**: support toolkit + a "Document jobs & AI runs" tab replacing Agent Audits |
| — | Ask AI chat (floating) | ASK D3 |
| — | Financial News + trend outlook | ASK D3 |
| — | Companies list (search/sort) | MERGE into their By-CP list (search exists there) |
| Dev user switcher (impersonate RM/WRM) | — | DROP in prod (dev only) → real SSO |

## 2. Covenant definition fields

| Field | Theirs | Ours | Decision |
|---|---|---|---|
| Name, Description | ✔ | ✔ | CORE |
| Category FINANCIAL / NON_FINANCIAL | ✔ | ✔ | CORE |
| Assessment type AUTO / MANUAL (manual = instruction text) | ✔ | ✗ (non-financial = "upload = compliant") | **CORE** (fixes our F-03) |
| Condition formula with live validation, examples, functions (AVG/SUM/MIN/MAX/LATEST/CURRENT/COUNT), windows ('90d','6m','2y'), sampling FLAT/PERIODIC… | ✔ | Simple `A / B` formula | CORE |
| Metric key, threshold separate fields | Threshold lives inside formula; threshold editor extracts it | ✔ separate | CORE (thresholds via `/formulas/thresholds`) |
| Default monitoring frequency (Daily … Custom cron) | ✔ | ✗ | CORE |
| Eligibility: Islamic banking, Segment, Industry, Country of risk, Country of incorporation, CP type, Legal entity type, Facility type, Collateral type ("All" wildcard) | ✔ | ✗ | CORE |
| Expression change log (when, who, old, new) | ✔ | ✗ | CORE |
| "Applied on N CPs" + impact warning on edit | ✔ | ✗ | CORE |
| Source page + quote from agreement | ✗ | ✔ | **BRING** (Flow A) |
| Excel export of master rules | ✗ | ✔ | **BRING** |

## 3. Company / CP fields

| Field | Theirs (from CP service) | Ours (typed on Add Company) | Decision |
|---|---|---|---|
| ID | CP ID (from bank CP system), Obligor ID | Borrower ID `BRW-n`, Facility ID `FAC-n` (auto-generated) | **CORE**: CP/obligor from CP service; ASK D5 if no CP service in pilot |
| Name | ✔ | ✔ Company Name | CORE |
| Business segment, sector, approval status, tags | ✔ | Industry (Gulf list: TMT, Energy, Real Estate, Healthcare, Retail…) | CORE + map our industry list to their sector values |
| RM / WRM (name, email) | ✔ | ✗ (hard-coded names in fake e-mail dialog) | CORE |
| Parent CP (renewal lineage) | ✔ | ✗ | CORE |
| Loan amount, currency (AED/USD/EUR/GBP/INR) | Facility amount + unit from attributes service (INR crore in demo) | ✔ typed | **ASK D6** (reporting currency and units) |
| Booking region (UAE, KSA, Qatar, Kuwait, Bahrain, Oman, India, Other) | ✗ | ✔ | BRING as CP attribute (if not in CP service) |
| Entity type, loan account no, sanction date | partial (attributes map) | ✔ | BRING where not in CP service |
| Effective / maturity date | ✗ | maturity = upload + 90 days (made up) | NEW: take from agreement extraction or CP service; never invent |

## 4. Applying covenants to a CP

| Feature | Theirs | Ours | Decision |
|---|---|---|---|
| Recommendation by eligibility (match reason) | ✔ | ✗ | CORE |
| ML threshold recommendations | ✔ (mock service) | ✗ | CORE (optional) |
| Covenants extracted from agreement with page + quote | ✗ | ✔ | **BRING** (C1, source EXTRACTED) |
| Per-row: select, definition for this CP, condition type (precedent / subsequent), classification (external / internal), frequency, monitoring start date | ✔ | Select only | CORE |
| Add from library / add custom covenant (Auto or Manual) | ✔ (per CP) | ✔ (global "Add Covenant") | CORE per-CP + library authoring for global |
| Revert to master; "already applied" badge | ✔ | ✗ | CORE |
| Upload to linked facilities | ✗ | Dummy toggle (does nothing) | **ASK D7** (real requirement?) |

## 5. Data input

| Feature | Theirs | Ours | Decision |
|---|---|---|---|
| Bank data sources (REST, MSSQL; columnar / key-value; CP or obligor grain) | ✔ | ✗ | CORE |
| Upload financial statement (Excel / PDF) per cycle | ✗ | ✔ (7 fields, 1 period) | **BRING** via DPS Flow B, extended to many line items |
| Upload loan agreement PDF | ✗ | ✔ | **BRING** via DPS Flow A |
| Upload non-financial evidence per covenant | ✔ (attachments, 10 MB, stored) | ✔ (discarded) | CORE |
| CP-level compliance certificate | ✔ | ✔ (per cycle, discarded) | CORE + ASK D8 (per cycle or per CP?) |
| Manual entry of missing values | ✗ (missing → ERROR; fix data and re-run) | ✔ (HITL value entry) | **ASK D9** |

## 6. Assessment and results

| Feature | Theirs | Ours | Decision |
|---|---|---|---|
| Result states | COMPLIANT / BREACHED / ERROR / PENDING_REVIEW / NA | COMPLIANT / BREACH / NOT_EVALUATED | CORE |
| Run now; scheduled runs; 06:00 sweep | ✔ | Manual "Run Analysis" only | CORE |
| Assessment detail: definition at run, tracked value, measured values snapshot, triggered by, scheduled/executed at, workflow link | ✔ | Report JSON | CORE |
| Per-comparison rows (measure, operator, threshold, actual) | ✔ | ✔ all_metrics | CORE |
| Headroom | ✔ | ✔ (gap) | CORE |
| Timing: Not started / Up to date / Due soon / Overdue / Ended | ✔ | ✗ | CORE |
| Trend direction and future prediction | ✗ | ✔ (heuristic) | **ASK D3** (advisory) |
| Confidence score per metric | ✗ | ✔ (heuristic) | **ASK D3** (advisory; label clearly) |
| LLM risk summary and action points | ✗ | ✔ | **BRING** (advisory, Flow B Report) |
| Compare selected cycles; cycle Excel export | ✗ (cycle history = CP lineage only) | ✔ | **BRING** (period comparison by `asOfDate`) |

## 7. Breach handling

| Feature | Theirs | Ours | Decision |
|---|---|---|---|
| Waiver / Extension per covenant, tied to latest assessment, note, audit | ✔ | Whole report (bug) / on-screen only (dialog doesn't save) | **CORE** |
| Maker-checker approval | ✗ | Names shown in fake e-mail dialog only | **ASK D4** |
| AI status suggestions (confidence, rationale) | ✔ interface (mock) | ✗ | CORE + DPS implements (C3) |
| Status audit (old → new, source SYSTEM / USER / AI, who) | ✔ | Partial | CORE |
| Reminder / e-mail to RM | ✔ real (notification service, daily, audited) | Fake dialog with hard-coded recipients | **CORE**; DROP ours |

## 8. Portfolio views

| Widget | Theirs | Ours | Decision |
|---|---|---|---|
| By-CP scorecard (applied, compliant, breached (handled), error, pending) | ✔ | Facility covenant status table | CORE |
| Agenda / calendar (overdue, due this week, upcoming; hide handled) | ✔ | Due Soon list (wrong date, F-06) | CORE |
| Portfolio health card (monitored events, breached, compliance rate) | ✔ | KPI row | CORE |
| Industry risk treemap; loan exposure by sector; entity type pie | ✗ | ✔ | **ASK D3** |

## 9. AI and audit

| Feature | Theirs | Ours | Decision |
|---|---|---|---|
| Ask AI chat over portfolio | ✗ | ✔ (sends whole portfolio, about 98k chars per question) | **ASK D3** (rebuild over Core APIs with small context) |
| Financial news + trend outlook | ✗ | ✔ (Google News RSS + LLM) | **ASK D3** |
| Agent audit screens | ✗ | ✔ | REPLACE with "Document jobs & AI runs" (DPS job steps, tokens, cost) |
| Ops toolkit (diagnose, re-ingest, re-assess, cleanup, health) | ✔ | ✗ | CORE (after token fix) |

## 10. Settings and admin

| Setting | Theirs | Ours | Decision |
|---|---|---|---|
| Fiscal year start, include current period, due-soon days, upcoming window | ✔ | ✗ | CORE |
| Reporting currency / unit (AED Mn) | ✗ (unit per measure) | Hard-coded "AED Mn" in prompts | **ASK D6** |
| Custom covenant catalog | ✔ library | ✔ JSON | CORE |
| Users / roles admin | ✗ | ✗ | NEW (via Entra ID groups) |

## 11. Things neither app has (gaps to add)

| # | Gap | Why it matters |
|---|---|---|
| G1 | Real login and roles (RM, approver, author, support, auditor) | Both apps trust headers or have no auth |
| G2 | Covenant definitions from agreement (e.g. how "EBITDA" is defined) | Ratios computed with wrong definitions |
| G3 | Testing period (quarterly vs last-12-months), step-down thresholds, cure periods, grace days | Common in real agreements |
| G4 | Amendments and waivers as new agreement versions | Thresholds change over the loan's life |
| G5 | Facility maturity and test dates taken from the agreement | Ours invents maturity; theirs needs manual dates |
| G6 | Notification preferences and escalation (e.g. overdue 30 days → credit head) | Only one daily RM e-mail today |
| G7 | Reports for management (portfolio breach report PDF/Excel by period) | Only grid views and cycle Excel |
| G8 | Data retention and deletion policy for documents | Bank requirement |
| G9 | Accessibility (WCAG AA) checks | Not tested in either |
