# Sample payloads — ML threshold recommendations & AI status suggestions

Reference request/response bodies for the two external-service integrations. **Important:** the
browser (frontend) calls the *backend* with little or no body — the backend assembles the real
payload server-side and calls the external service. The examples below are the **backend → external
service** requests/responses, i.e. what you see in the WireMock request journal
(`http://localhost:9311/__admin/requests`) or in an outbound HTTP trace.

- Field names are the exact JSON produced/consumed by the DTOs (`dto/**`).
- Enum values: `AssessmentResult` = `COMPLIANT | BREACHED | NA | ERROR | PENDING_REVIEW`;
  `CovenantDispositionStatus` = `COMPLIED | BREACHED | WAIVER | EXTENSION`.

---

## 1. ML threshold recommendations (US-ML-12)

**Frontend → backend:** `POST /api/v1/onboarding/cp/{cpId}/ml-threshold-recommendations`
(body = `MlRecommendationRequest`: the in-scope covenants selected in the wizard).

**Backend → external ML service:** `POST {covenant.clients.ml.base-url}/threshold-recommendations`
(body = `MlThresholdRequest`; the backend enriches with CP attributes and extracts `thresholds` from
each expression via the formula engine). Dev base-URL →
`http://localhost:{server.port}/internal/mock-ml`.

**Response:** a bare JSON **array** of `MlThresholdRecommendation` (the client reads it with
`bodyToFlux`). On any failure the client returns `[]` so onboarding still proceeds.

### Example 1 — a single numeric floor (DSCR), tightened

Request (backend → ML):
```json
{
  "cpId": "CP-1001",
  "cpAttributes": {
    "businessSegment": "Corporate",
    "sector": "Infrastructure",
    "countryOfRisk": "AE",
    "creditProposalType": "TermLoan"
  },
  "covenants": [
    {
      "covenantMasterId": 12,
      "covenantName": "Minimum DSCR",
      "currentExpression": "DSCR >= 1.5",
      "thresholds": [
        { "label": "DSCR", "operator": ">=", "value": "1.5", "valueType": "NUMBER" }
      ]
    }
  ],
  "appliedContext": []
}
```

Response (ML → backend):
```json
[
  {
    "covenantMasterId": 12,
    "recommendedExpression": "DSCR >= 1.575",
    "confidence": 0.92,
    "rationale": "Adjusted 1 threshold(s) from the CP profile and peer benchmarks."
  }
]
```

### Example 2 — an upper bound (leverage), loosened + read-only applied context

Request:
```json
{
  "cpId": "CP-1002",
  "cpAttributes": {
    "businessSegment": "MidCorp",
    "sector": "Manufacturing",
    "countryOfIncorporation": "IN"
  },
  "covenants": [
    {
      "covenantMasterId": 20,
      "covenantName": "Maximum Leverage",
      "currentExpression": "AVG(TOTAL_DEBT, '1y') / AVG(EBITDA, '1y') <= 3.5",
      "thresholds": [
        { "label": "AVG(EBITDA, '1y')", "operator": "<=", "value": "3.5", "valueType": "NUMBER" }
      ]
    }
  ],
  "appliedContext": [
    {
      "covenantMasterId": 7,
      "covenantName": "Minimum Current Ratio",
      "currentExpression": "CURRENT_RATIO >= 1.2",
      "thresholds": [
        { "label": "CURRENT_RATIO", "operator": ">=", "value": "1.2", "valueType": "NUMBER" }
      ]
    }
  ]
}
```

Response:
```json
[
  {
    "covenantMasterId": 20,
    "recommendedExpression": "AVG(TOTAL_DEBT, '1y') / AVG(EBITDA, '1y') <= 3.325",
    "confidence": 0.80,
    "rationale": "Adjusted 1 threshold(s) from the CP profile and peer benchmarks."
  }
]
```

### Example 3 — mixed batch; a non-numeric covenant is left unchanged

Request:
```json
{
  "cpId": "CP-1003",
  "cpAttributes": { "businessSegment": "Corporate", "islamicBanking": "true" },
  "covenants": [
    {
      "covenantMasterId": 31,
      "covenantName": "Minimum Interest Coverage",
      "currentExpression": "AVG(EBIT, '2y') / AVG(INTEREST_EXPENSE, '2y') > 2",
      "thresholds": [
        { "label": "AVG(INTEREST_EXPENSE, '2y')", "operator": ">", "value": "2", "valueType": "NUMBER" }
      ]
    },
    {
      "covenantMasterId": 45,
      "covenantName": "Audited Financials Submitted",
      "currentExpression": "AUDITED_FILED == true",
      "thresholds": [
        { "label": "AUDITED_FILED", "operator": "==", "value": "true", "valueType": "BOOLEAN" }
      ]
    }
  ],
  "appliedContext": []
}
```

Response (numeric floor tightened +5%; boolean covenant returned unchanged):
```json
[
  {
    "covenantMasterId": 31,
    "recommendedExpression": "AVG(EBIT, '2y') / AVG(INTEREST_EXPENSE, '2y') > 2.1",
    "confidence": 0.91,
    "rationale": "Adjusted 1 threshold(s) from the CP profile and peer benchmarks."
  },
  {
    "covenantMasterId": 45,
    "recommendedExpression": "AUDITED_FILED == true",
    "confidence": 0.85,
    "rationale": "No numeric thresholds to tune; covenant left unchanged."
  }
]
```

> The dev mock (`MockMlThresholdController`) is deterministic: numeric floors (`>`,`>=`) ×1.05,
> numeric bounds (`<`,`<=`) ×0.95, BOOLEAN/DATE/STRING left as-is; confidence is a stable
> pseudo-value in `[0.80, 0.99]` derived from `covenantMasterId`.

---

## 2. AI status suggestions (US-MON-18)

**Frontend → backend:** `POST /api/v1/monitoring/cp/{cpId}/ai-suggestions` — **no request body**
(`cpId` is the path variable). This is why the browser Network tab shows an empty payload.

**Backend → external AI service:** `POST {covenant.clients.ai-suggestion.base-url}/api/v1/ai/covenant-status`
(body = `AiStatusRequest`, assembled by `AiSuggestionService` from the review grid + uploaded
fileIds). Dev base-URL → `http://localhost:9311` (WireMock stub `ai-suggestions.json`).

**Response:** an `AiSuggestionResponse` **object** with a `recommendations` array. The backend
filters recommendations to covenants actually on the CP; on any failure it returns `[]` and the modal
shows "AI unavailable".

### Example 1 — a breach with evidence → recommend WAIVER

Request (backend → AI):
```json
{
  "cpId": "CP-1001",
  "certificateFileIds": ["cert-a1b2c3"],
  "covenants": [
    {
      "appliedCovenantId": 1,
      "covenantName": "Minimum DSCR",
      "covenantDescription": "Debt Service Coverage Ratio must stay at or above 1.5x, tested quarterly on a trailing-twelve-month basis.",
      "conditionExpression": "DSCR >= 1.5",
      "computedValue": "1.42",
      "latestResult": "BREACHED",
      "attachmentFileIds": ["file-9f8e7d"]
    }
  ]
}
```

Response (AI → backend):
```json
{
  "recommendations": [
    {
      "appliedCovenantId": 1,
      "recommendedStatus": "WAIVER",
      "rationale": "Borrower cured the shortfall within the contractual cure period; a waiver is recommended.",
      "confidence": 0.82
    }
  ]
}
```

### Example 2 — a breach with a remediation plan → recommend EXTENSION

Request:
```json
{
  "cpId": "CP-1002",
  "certificateFileIds": [],
  "covenants": [
    {
      "appliedCovenantId": 2,
      "covenantName": "Maximum Leverage",
      "covenantDescription": "Total debt to EBITDA must not exceed 3.5x; a step-up margin applies on any breach pending cure.",
      "conditionExpression": "TOTAL_DEBT / EBITDA <= 3.5",
      "computedValue": "3.9",
      "latestResult": "BREACHED",
      "attachmentFileIds": ["file-plan-2025q2"]
    }
  ]
}
```

Response (Extension still requires the RM to set a date before applying — US-MON-16 AC9):
```json
{
  "recommendations": [
    {
      "appliedCovenantId": 2,
      "recommendedStatus": "EXTENSION",
      "rationale": "Temporary liquidity pressure with a credible remediation plan; a short extension is recommended.",
      "confidence": 0.74
    }
  ]
}
```

### Example 3 — mixed grid; complied row + breach, and an "unavailable" fallback

Request (two covenants; one compliant, one breached):
```json
{
  "cpId": "CP-1003",
  "certificateFileIds": ["cert-2024-annual"],
  "covenants": [
    {
      "appliedCovenantId": 5,
      "covenantName": "Minimum Current Ratio",
      "covenantDescription": "Current assets to current liabilities must remain at or above 1.2x to evidence short-term liquidity.",
      "conditionExpression": "CURRENT_RATIO >= 1.2",
      "computedValue": "1.35",
      "latestResult": "COMPLIANT",
      "attachmentFileIds": []
    },
    {
      "appliedCovenantId": 6,
      "covenantName": "Minimum Interest Coverage",
      "covenantDescription": "EBIT must cover interest expense by more than 2.0x, evidencing capacity to service debt from operating earnings.",
      "conditionExpression": "EBIT / INTEREST_EXPENSE > 2",
      "computedValue": "1.7",
      "latestResult": "BREACHED",
      "attachmentFileIds": ["file-icr-note"]
    }
  ]
}
```

Successful response (AI leaves the compliant row alone, recommends a disposition on the breach):
```json
{
  "recommendations": [
    {
      "appliedCovenantId": 6,
      "recommendedStatus": "WAIVER",
      "rationale": "One-off interest spike expected to normalise next quarter; waiver recommended.",
      "confidence": 0.68
    }
  ]
}
```

Graceful-failure response (AI service slow/down — circuit breaker/timeout trips, US-MON-18 AC6). The
backend returns an empty list and the modal shows "AI unavailable":
```json
{ "recommendations": [] }
```

---

## Where each field comes from

| DTO | Field | Source |
|---|---|---|
| `MlThresholdRequest` | `cpAttributes` | CP attributes cache (`CachedCovenantAttributesService`) |
| `MlCovenantContext` | `currentExpression` | per-CP override else `CovenantMaster` condition |
| `MlCovenantContext` | `thresholds` | `FormulaEngine.thresholds(currentExpression)` (server-side) |
| `MlThresholdRecommendation` | `recommendedExpression` | ML service (same measures, new literals) |
| `AiStatusRequest.Covenant` | `covenantDescription` | `CovenantMaster.getDescription()` (server-side lookup by `covenantMasterId`) |
| `AiStatusRequest.Covenant` | `computedValue`, `latestResult` | latest `Assessment` for the covenant |
| `AiStatusRequest` | `certificateFileIds` / `attachmentFileIds` | `cov_compliance_certificate` / `cov_covenant_attachment` |
| `AiStatusRecommendation` | `recommendedStatus` | AI service (`WAIVER`/`EXTENSION`/…) — applied with `statusSource = AI` |
