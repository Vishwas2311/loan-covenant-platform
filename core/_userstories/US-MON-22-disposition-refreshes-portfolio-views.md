# US-MON-22 — Recording a disposition refreshes the portfolio views live

**Epic:** F — RM assessment, disposition & evidence
**Status:** Draft · **Priority:** High

## Story
**As a** Relationship Manager who just recorded a Waiver/Extension in a CP's assessment grid
**I want** the By-CP and By-Covenant portfolio views to reflect that change when I navigate back to
them
**so that** the workspace feels consistent — my action shows everywhere immediately, not only on the
grid I was working on.

## Description
The disposition write in `DispositionModal.tsx` (`PUT .../covenants/{appliedId}/status`) currently, on
success, invalidates **only** the CP review-grid query
(`invalidateQueries(['monitoring','cp',cpId,'review'])`) and closes. The portfolio-level TanStack
Query caches — By-CP summary (`['monitoring','summary']`) and By-Covenant schedule
(`['monitoring','schedule', from, to]`) — are left stale, so a user who navigates back to those views
sees the old numbers until a manual refetch (mount / "Run & notify" / "Run now"). This is the "A" half
of the fix: once US-MON-20/21 make those views disposition-aware, they must also **refresh** after a
disposition.

The change is small and frontend-only: on a successful disposition save, additionally invalidate the
summary and schedule query keys so both portfolio views refetch their now-disposition-aware payloads.

## Acceptance criteria

### AC1 — By-CP view refreshes after a disposition
```
Given I have the By-CP view loaded, then drill into a CP and Waive a breached covenant
When the disposition saves successfully and I return to the By-CP view
Then it has refetched and shows the updated handled count (US-MON-20) without a manual refresh.
```

### AC2 — By-Covenant view refreshes after a disposition
```
Given I have the By-Covenant view loaded, then drill in and Extend a breached covenant
When the disposition saves successfully and I return to the By-Covenant view
Then it has refetched and shows the new disposition badge (US-MON-21) without a manual refresh.
```

### AC3 — Existing grid refresh still works
```
Given I record a disposition
When it saves successfully
Then the CP review grid still refreshes (existing behaviour) and the modal closes as before.
```

## Data / API notes
- Frontend only — **no backend change.** In `DispositionModal.tsx` `onSuccess`, alongside the existing
  `invalidateQueries({ queryKey: ['monitoring','cp',cpId,'review'] })`, add:
  - `invalidateQueries({ queryKey: ['monitoring','summary'] })`
  - `invalidateQueries({ queryKey: ['monitoring','schedule'] })` (prefix match invalidates every
    `from/to` variant).
- No new endpoints, no type changes.

## Dependencies
- **US-MON-20 / US-MON-21** — the disposition-aware payloads these invalidations refetch. Shipped
  alone (before 20/21) this is a harmless no-op: the views refetch but render the same numbers, so
  ship it **with** 20/21.

## Out of scope (flag for follow-up)
- Optimistic UI updates of the portfolio views (we refetch, we don't hand-patch the cache).
- Any backend recomputation — dispositions still never touch `cov_assessment` (US-MON-16).

---

## Testing / quality-gate notes (US-MON-20/21/22)
- **Backend (JaCoCo 0.95 line / 0.90 branch; Mockito STRICT_STUBS):** extend `MonitoringServiceTest`
  with the disposition-aware roll-up — cases for an **active** disposition (counts as handled /
  badged), a **lapsed** disposition against an older assessment (does **not** count), and **no**
  disposition (unchanged vs today). Assert the batched `findByCpId` lookup is used (no N+1). Cover the
  shared `dispositionAddressesCurrentBreach` helper once it is factored out of `ReviewService`.
- **Frontend (ESLint max-warnings=0; Vitest 95/85/75/95):** `MonitoringDashboard` test asserts the
  handled count renders and the "all handled" treatment; `MonitoringSchedulePage` test asserts the
  Waiver/Extension badge and the "Hide handled" toggle; `DispositionModal` test asserts `onSuccess`
  invalidates all three query keys (`review`, `summary`, `schedule`).
- **Demo data:** the CP-20xx dev dataset already provides 4 dispositionable breaches per CP
  (CP-2001..2010), so all three stories can be exercised end-to-end without new seed data.
