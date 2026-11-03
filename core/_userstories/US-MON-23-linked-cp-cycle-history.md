# US-MON-23 — Read-only historical cycle viewer for linked CPs

**Epic:** F — RM assessment, disposition & evidence
**Status:** Draft · **Priority:** Medium

## Story
**As a** Relationship Manager opening the newest Credit Proposal in a linked chain
**I want** to view every earlier (parent) CP's covenant assessment data as read-only "cycles"
**so that** I can see the obligor's full monitoring history across renewals from one screen — without
switching CPs and without any risk of changing historical data.

## Description
An obligor is renewed/refinanced over time, producing a chain of **linked CPs** where each CP is the
*parent* of the next (e.g. `CP_1003` → `CP_1002` → `CP_1001`). Opening the newest CP's assessment grid
(US-MON-16) exposes a **Cycle history** action that opens a full-screen viewer showing each CP in the
chain as **Cycle 1, Cycle 2, Cycle 3 …** (Cycle 1 = oldest root; the opened CP is the highest cycle,
flagged *current*). Lineage is resolved from an external CP-service `parentCpId`, denormalised on the CP
cache and walked upward (ancestors only) with cycle/loop guards.

Each cycle renders **exactly** the US-MON-16 grid (expression, actual, headroom, derived status, timing,
attachments) plus the US-MON-17 CP compliance certificates — but **strictly read-only**: no disposition,
no AI suggestions, no upload/remove. The one permitted action is **downloading** evidence/certificate
files (a capability built here and reused on the live screen). The whole lineage is viewable even if
some ancestor CPs fall outside the RM's portfolio scope (consistent with today's un-scoped review
endpoint); the cycle endpoints are guarded so only CPs in the opened CP's lineage can be read.

### Cycle numbering
The lineage is walked **root-ward** (from the opened CP up through its parents to the root). The root
(oldest) CP is **Cycle 1**; each child increments the number; the opened CP is the **highest** cycle and
is flagged *current*. Descendant CPs (renewals newer than the opened CP, if any) are **not** shown —
this screen answers "show me the history behind the CP I opened".

## Acceptance criteria

### AC1 — Entry point
```
Given I am on a CP's assessment grid (US-MON-16)
When I click "Cycle history"
Then a full-screen viewer opens listing the CP's lineage as cycles.
```

### AC2 — Lineage & cycle numbering
```
Given the opened CP has parent CPs forming a chain
Then the oldest (root) CP is Cycle 1 and each child is the next cycle
And the opened CP is the highest-numbered cycle, marked "(current)".
```

### AC3 — Cycle navigation
```
Given the viewer is open
When I select a cycle in the rail
Then the right pane loads that cycle CP's read-only assessment data.
```

### AC4 — Read-only grid mirrors US-MON-16
```
Given a selected cycle
Then I see one row per covenant with Covenant · Expression · Actual · Headroom · Status ·
     Start · Next due · Last assessed · Attachments
And no cell is editable (no disposition control, no AI action).
```

### AC5 — Read-only evidence & certificates, download only
```
Given a selected cycle
Then per-covenant attachments and CP compliance certificates are listed
And each file offers Download only — no upload and no remove.
```

### AC6 — File download works (and is reused live)
```
Given any listed evidence/certificate file (in a cycle or on the live grid)
When I click Download
Then the file is fetched through the backend and saved with its original filename.
```

### AC7 — No write affordances in the viewer
```
Given the viewer is open on any cycle (including the current one)
Then disposition editing, AI suggestions, upload and remove are all absent.
```

### AC8 — Lineage-membership guard
```
Given a request for a cycle CP that is not in the opened CP's lineage
Then the request is rejected (not found) rather than returning that CP's data.
```

### AC9 — Single CP (no parents)
```
Given the opened CP has no parent
Then the viewer shows a single "current" cycle with an empty-state note ("No linked prior CPs").
```

### AC10 — Missing / unavailable data is handled gracefully
```
Given lineage resolution cannot reach the CP service for an ancestor
Then the chain ends at the last resolvable CP (no error is shown)
And a cycle whose covenants were never assessed shows "—" for actual/headroom
And if the file service is unavailable a download shows a clear error without breaking the viewer.
```

## Data / API notes
- Add `parentCpId` to `CreditProposalDetails`; denormalise as `cov_credit_proposal_cache.parent_cp_id`
  (**V12** migration) populated by `CachedCovenantAttributesService.enrichOwnership` (null-preserving).
- New read-only endpoints on the monitoring controller:
  - `GET /api/v1/monitoring/cp/{cpId}/cycles` → `CpCycleView[]` (`{cycleNumber, cpId, current}`).
  - `GET /api/v1/monitoring/cp/{cpId}/cycles/{cycleCpId}` → `CycleReviewResponse`
    (`{cpId, cycleNumber, cpName, review: CpReviewRow[], certificates: AttachmentView[]}`); guarded by
    lineage membership.
- New download endpoints (reused on the live grid):
  `GET …/cp/{cpId}/covenants/{appliedId}/attachments/{attachmentId}/download` and
  `GET …/cp/{cpId}/compliance-certificate/{certificateId}/download` → `ResponseEntity<Resource>` with
  `Content-Disposition`.
- New `CpLineageService` (walk parents; cache-first + live fallback; `visited` + `MAX_DEPTH` guards) and
  `CpCycleService`; reuses `ReviewService.reviewRows(cpId)` and `AttachmentService.certificatesForCp`.
- New `FileUploadClient.download(fileId)` (`GET /api/v1/files/{fileId}`) + `FileDownloadResult`.
- Frontend: `cyclesApi`, `apiDownload` (blob + `Content-Disposition`), `CycleHistoryModal`, and a
  `readOnly` mode on the attachments components.

## Dependencies
- **US-MON-16** — the assessment grid this viewer mirrors read-only.
- **US-MON-17** — evidence/certificates and the external file service (now extended with download).
- **US-MON-15** — the workspace that drills into the CP assessment screen.
- New dependency: the external **CP service exposes `parentCpId`** for lineage.

## Out of scope (flag for follow-up)
- Editing anything in a historical cycle (viewer is strictly read-only).
- Descendant CPs — only ancestors (root-ward) are shown, matching "open the newest CP".
- Cross-cycle diff/side-by-side comparison of covenant values.
- A persisted lineage graph table — lineage is walked via `parentCpId`; no new `cov_` table is added.
