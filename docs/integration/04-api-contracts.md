# 04: API Contracts Between Covenant Core and Document Processing Service

Conventions for all contracts:
- JSON over HTTPS through the internal network. Service-to-service authentication uses a client-credentials token (Entra ID); user calls carry the user token via the gateway.
- Every request carries `X-Correlation-Id`.
- Dates are ISO `YYYY-MM-DD`; timestamps are ISO-8601 UTC.
- Errors use Core's envelope `{code, message, details, timestamp}`.
- **Status:** C2 and C4 already exist in Core (C2 proven live). C1, C3 and C5 need small Core changes. DPS endpoints are new.

## C1: Covenant proposals (Core → DPS, during onboarding)

Core change: add an `ExtractionRecommender` next to `MlThresholdRecommender`, plus `ThresholdSource.EXTRACTED` and grid source `"EXTRACTED"`. Reuse `MlThresholdService.isValidSameMeasure`, `ThresholdAudit` RECOMMENDED/APPLIED events, and the merge in `OnboardingRecommendationService`.

`POST /dps/v1/recommendations`
```json
{ "cpId": "CP-1001" }
```
`200`: only `PUBLISHED` proposals from the CP's latest agreement documents.
```json
[{
  "covenantMasterId": "c0000000-0000-0000-0000-000000000001",
  "recommendedExpression": "DSCR >= 1.25",
  "confidence": 0.93,
  "rationale": "Clause 9.2: Borrower shall maintain DSCR of not less than 1.25x",
  "frequency": "QUARTERLY",
  "source": { "documentId": "doc-7f1", "fileId": "f-123", "page": 14,
              "quote": "maintain a Debt Service Coverage Ratio of not less than 1.25x" },
  "customCovenant": null
},
{
  "covenantMasterId": null,
  "recommendedExpression": null,
  "confidence": 0.81,
  "rationale": "EBITDA to EMI covenant not in library",
  "frequency": "QUARTERLY",
  "source": { "documentId": "doc-7f1", "fileId": "f-123", "page": 15, "quote": "EBITDA/EMI ≥ 1.20x" },
  "customCovenant": { "name": "EBITDA to EMI floor", "definition": "EBITDA / EMI_AMOUNT >= 1.20",
                      "assessmentType": "AUTO" }
}]
```
Rules:
- Core shows rows with a source link.
- The RM accepts or edits. Accepted rows are applied with `thresholdSource = EXTRACTED`; edited rows with `USER`.
- `customCovenant` maps to Core's existing `POST /credit-proposals/{cpId}/custom-covenants`.
- If DPS times out (3 s) or fails, Core continues without EXTRACTED rows (same resilience as ML).

## C2: Financial values (Core → DPS) — *existing Core contract*

Register DPS in Core once: `POST /api/v1/data-sources` with `{type:"REST", connectionConfig:{baseUrl:"https://di.internal", valuesPath:"/dps/v1/values"}}`. Add one table per grain (`entityKeyLevel` = `CP_ID` or `OBLIGOR_ID`, layout `COLUMNAR`). Add measures whose `sourceColumn` = DPS line-item key.

`POST /dps/v1/values` (request sent by Core's `WebClientDataApiClient`)
```json
{ "cpId": "CP-1001", "from": "2025-04-01", "to": "2026-03-31", "keys": ["EBITDA", "INTEREST_PAID", "PRINCIPAL_PAID"] }
```
- `from` absent = point-in-time: return the single latest point with `asOfDate <= to`.
- `cpId` holds the obligor id when the table is OBLIGOR_ID-keyed.

`200`
```json
{ "cpId": "CP-1001",
  "points": [
    { "asOfDate": "2025-12-31", "values": { "EBITDA": 205.0, "INTEREST_PAID": 40.1, "PRINCIPAL_PAID": 80.0 } },
    { "asOfDate": "2026-03-31", "values": { "EBITDA": 210.0, "INTEREST_PAID": 41.0, "PRINCIPAL_PAID": 80.0 } } ],
  "missingKeys": [],
  "sourceChangedAt": "2026-04-12T10:03:00Z" }
```
Rules:
- Only values from `REPORTED` (validated) statements are served; the latest version per period wins.
- Values are in the measure's declared `unit`.
- `sourceChangedAt` = time of the newest statement for the entity. Core's freshness gate re-ingests when it moves.
- Response time target p95 ≤ 500 ms.
- Errors: 5xx makes Core record `DATA_SOURCE_FAILURE` (existing behaviour). Never return partial invented values.

Optional `GET /dps/v1/catalog` → `{columns:[{key,label,dataType,kind:"METRIC"}]}` for Core's column discovery.

## C3: AI status suggestions (Core → DPS) — *Core interface exists, implementation mocked*

Core calls through `AiStatusRecommender` with the CP review rows plus certificate/evidence `fileIds`.
```json
{ "cpId": "CP-1001", "certificateFileIds": ["f-900"],
  "covenants": [{ "appliedCovenantId": "ac-1", "covenantName": "Quarterly MIS Submission",
                  "covenantDescription": "...", "conditionExpression": "...", "computedValue": null,
                  "latestResult": "BREACHED", "attachmentFileIds": ["f-901"] }] }
```
`200`
```json
[{ "appliedCovenantId": "ac-1", "recommendedStatus": "WAIVER",
   "rationale": "Waiver letter f-901 p.1 dated 2026-04-10 covers this breach", "confidence": 78.0 }]
```
Field names match Core's `AiStatusRequest` / `AiStatusRecommendation` records (checked in code). `recommendedStatus` is a `CovenantDispositionStatus`: COMPLIED / BREACHED / WAIVER / EXTENSION.

Rule: suggestion only. A human applies it; Core stores `statusSource = AI`. Core only allows a disposition on a BREACHED row. For MANUAL covenants a suggestion helps the reviewer, but completion stays the manual-assessment action with its mandatory note.

## C4: Library and measures (DPS → Core) — *existing*

- `GET /api/v1/covenants` (active masters, condition expressions, assessment type)
- `GET /api/v1/measures` (codes, units, data types)
- `POST /api/v1/formulas/validate {expression, requireBoolean:true}` → `{valid, errors, …}`, used by Flow A Validate
- `GET /api/v1/credit-proposals/{cpId}/applied-covenants`, used by Flow B Validate to know which line items this CP needs

DPS caches the library for 5 minutes.

## C5: Data-changed event (DPS → Core) — *new Core endpoint*

Why: Core's breach run treats an ERROR run as "assessed" (`BreachNotificationService.isDue`), and `IngestionOrchestrationService.recordSourceChange` has no public endpoint. Without C5, a statement uploaded after the due date would wait for the next cron date, which can be a full quarter.

`POST /api/v1/data-events`
```json
{ "dataSourceId": "d5-di", "entityKey": "CP-1001", "entityKeyLevel": "CP_ID",
  "asOfDate": "2026-03-31", "changedAt": "2026-04-12T10:03:00Z", "documentId": "doc-88" }
```
Core behaviour:
1. `recordSourceChange` for each table of that source/entity.
2. Re-assess ACTIVE AUTO covenants on the CP (or on the obligor's CPs) that use measures from that source **and** whose latest result is ERROR, or whose window includes `asOfDate`.
3. Return `202 {assessmentsQueued}`.

Idempotent on (`documentId`, `changedAt`). Requires the SERVICE role.

## C6: Files — *existing file service*

Both services use the same file service. DPS uploads via the gateway (`POST /dps/v1/documents` multipart): DPS stores bytes in the file service and keeps `fileId`. Core attachments keep their existing flow. Allowed types and limits for documents: PDF/XLSX/XLS/CSV ≤ 50 MB, ≤ 500 pages.

## DPS public endpoints (via gateway, user token)

| Method | Path | Purpose |
|---|---|---|
| POST | `/dps/v1/documents` | Upload agreement or statement `{cpId, type: LOAN_AGREEMENT|FINANCIAL_STATEMENT|EVIDENCE, asOfDate?}` → `202 {documentId, jobId}` |
| GET | `/dps/v1/documents?cpId=` | List documents with status |
| GET | `/dps/v1/jobs/{jobId}` | Status, step timings, issues |
| GET | `/dps/v1/documents/{id}/terms` | Proposals with sources (review screen) |
| PATCH | `/dps/v1/terms/{termId}` | Reviewer edit / approve / reject (APPROVER or RM role) |
| POST | `/dps/v1/jobs/{jobId}/resume` | Resume a NEEDS_REVIEW run |
| GET | `/dps/v1/documents/{id}/values` | Statement values with sources |
| POST / DELETE | `/dps/v1/documents/{id}/links` `{cpIds: [...]}` | Link one agreement to several CPs (D7 / FR-21); unlinking keeps history |
| POST | `/dps/v1/cps/{cpId}/manual-values` `{asOfDate, key, value, unit, note}` | Approver-only manual entry of a missing line item (D9 / FR-20); stored with source USER; emits C5 |

All of these check that the user owns `cpId`, by calling Core's portfolio check or reading the CP cache.

## 6. Mapping tables

### 6.1 Covenant metric keys (ours → Core)
Our `config/covenant_master_defaults.json` copies Core's seeded library names; the keys differ.

| Our key | Core measure | Core master (seed) | Note |
|---|---|---|---|
| DSCR | DSCR | Minimum DSCR | |
| NET_LEVERAGE_RATIO | NET_LEVERAGE | Maximum Net Leverage | |
| ICR | INTEREST_COVER | Minimum Interest Coverage | |
| MIN_TANGIBLE_NET_WORTH | TANGIBLE_NET_WORTH | Minimum Tangible Net Worth | unit INR cr |
| CO2_INTENSITY | CO2_INTENSITY | CO2 Intensity Cap (SLT) | |
| CRUDE_OUTPUT | CRUDE_OUTPUT | Minimum Crude Steel Output | |
| REC_COVER | RECV_COVER | Export Receivables Cover | |
| EXPORT_LICENSE_VALID | EXPORT_LICENCE_VALID | Export Authorisation Valid | MANUAL |
| CURRENT_RATIO | CURRENT_RATIO | Current Ratio Floor | |
| QUICK_RATIO | QUICK_RATIO | Quick Ratio Floor | |
| DEBT_TO_EQUITY | DEBT_EQUITY | Debt to Equity Ceiling | |
| DEBT_TO_EBITDA | DEBT_EBITDA | Total Debt to EBITDA Ceiling | |
| DEBT_TO_NET_WORTH | — | — | New master + measure |
| EBITDA_TO_EMI | — | — | New master + measure |
| MIN_NET_WORTH, EBITDA_MARGIN, ROCE, PAT, … | — | Exists as MANUAL text | Add measures to make AUTO (phase 2) |
| Non-financial (NEGATIVE_PLEDGE, INSURANCE_MAINTENANCE, REPORTING_COVENANT, COMPLIANCE_CERTIFICATE, BOOKS_AND_RECORDS, CHANGE_OF_CONTROL, REGULATORY_AND_TAX_COMPLIANCE) | — | Core has similar MANUAL masters (e.g. "Insurance Cover Maintained", "No Change of Control") | Map by name; MANUAL |

### 6.2 Statement line-item keys served by C2 (initial set)
`REVENUE, EBITDA, EBIT, DEPRECIATION, INTEREST_PAID, PRINCIPAL_PAID, EMI_AMOUNT, PAT, TOTAL_DEBT, ST_DEBT, LT_DEBT, NET_DEBT, CASH, NET_WORTH, TANGIBLE_NET_WORTH, CURRENT_ASSETS, CURRENT_LIABILITIES, INVENTORY, RECEIVABLES, CAPEX`, each with a `unit`.

Each key becomes one Core **measure** (Core measures map to a column; they are not derived formulas). The covenant **condition** then combines them, e.g. `EBITDA / (INTEREST_PAID + PRINCIPAL_PAID) >= 1.25`. To avoid clashing with Core's existing `DSCR` measure (a bank-supplied ratio on the MSSQL source), DPS measures get their own codes, e.g. a `DI_` prefix such as `DPS_EBITDA`. Each applied covenant's formula decides which measures it uses.
