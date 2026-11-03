# US-MON-18 — AI-assisted covenant status suggestions

**Epic:** F — RM assessment, disposition & evidence
**Status:** Draft · **Priority:** Medium

## Story
**As a** Relationship Manager working a CP's covenants
**I want** an "AI Suggestion" action that returns a recommended status per covenant in a modal I can
accept or dismiss
**so that** I get decision support while keeping full control of the recorded outcome.

## Description
The CP assessment screen (US-MON-16) gains an **AI Suggestion** button. It calls our backend, which
calls the bank's **external AI service** with the CP's covenant data plus the **fileIds** already
uploaded (evidence attachments and/or the compliance certificate, US-MON-17). The service returns a
**recommended status per covenant** (with optional rationale/confidence), shown in a **modal** aligned
to each covenant row. The RM chooses **which** suggestions to apply — each applied suggestion updates
that covenant's disposition with source **AI** and is audited (US-MON-19) — or dismisses with no
change. The call is **resilient**: on failure the modal degrades gracefully and nothing changes.

## Acceptance criteria

### AC1 — AI Suggestion call with covenant data + fileIds
```
Given the CP assessment screen
When I click "AI Suggestion"
Then the backend calls the external AI service with the CP's covenant data and the uploaded fileIds
    (attachments and/or compliance certificate).
```

### AC2 — Per-covenant recommendation in a modal
```
Given the AI service responds
Then a modal shows a recommended status per covenant (with optional rationale/confidence)
And each recommendation is aligned to its covenant row.
```

### AC3 — User chooses what to apply, per covenant
```
Given the suggestions modal is open
Then I can override the current (system/user) status with the AI status per covenant, choosing which
     to apply
And I can dismiss the modal with no change to any covenant.
```

### AC4 — Applying an AI suggestion updates + audits with source AI
```
Given I apply an AI suggestion for a covenant
Then that covenant's disposition is updated
And the change is audited with source AI (US-MON-19)
And covenants whose suggestion I did not apply are unchanged.
```

### AC5 — AI Extension still needs an extension date
```
Given an AI suggestion recommends Extension for a covenant
Then an extension date is still required before it can be applied (US-MON-16 AC9).
```

### AC6 — Resilient; graceful failure
```
Given the AI service is slow, erroring, or down
When I click "AI Suggestion"
Then the call is protected by the standard timeout + retry + circuit breaker (covenant.clients.ai-suggestion)
And on failure the modal shows a graceful "AI unavailable" message
And there is no crash and no data change.
```

### AC7 — Configured base URL
```
Given deployment configuration
Then the AI base URL is set in application.yml (dev → WireMock, test → override).
```

## Data / API notes
- New external client **`ai/AiStatusRecommender`** + **`WebClientAiStatusRecommender`**: POST covenant
  data + uploaded fileIds → per-covenant recommended status / rationale / confidence; resilient and
  never throws (the ML/notification client pattern). DTOs `dto/AiStatusRequest`,
  `dto/AiStatusRecommendation`.
- New endpoint: `POST /api/v1/monitoring/cp/{cpId}/ai-suggestions` → returns per-covenant suggestions;
  applying a suggestion reuses the US-MON-16 status endpoint with `statusSource = AI`.
- Config: `covenant.clients.ai-suggestion.base-url` +
  `resilience4j.{circuitbreaker,retry,timelimiter}.instances.aiSuggestion` in `application.yml`, with
  `application-dev.yml` / `application-test.yml` overrides; WireMock stub
  `wiremock/mappings/ai-suggestions.json`.
- Frontend: an `AiSuggestionModal` reusing the grid's status-edit rules (Complied lock, Breached ⇒
  Waiver/Extension, Extension ⇒ mandatory date).

## Dependencies
- US-MON-16 — the grid and status/disposition rules AI suggestions apply through.
- US-MON-17 — provides the fileIds sent to the AI service.
- US-MON-19 — applied suggestions are audited with source `AI`.

## Out of scope (flag for follow-up)
- Auto-applying AI suggestions without user confirmation (always preview + confirm).
- Model governance / explainability beyond the returned rationale + confidence.
- Feeding disposition outcomes back to the AI service for retraining.
