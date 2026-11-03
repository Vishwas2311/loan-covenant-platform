# US-MON-17 — Evidence attachments & CP-level Compliance Certificate

**Epic:** F — RM assessment, disposition & evidence
**Status:** Draft · **Priority:** High

## Story
**As a** Relationship Manager recording a covenant disposition
**I want** to attach evidence to a covenant row and upload compliance-certificate file(s) at the CP
level via the bank's file-upload service
**so that** my decisions are backed by documents that reviewers and auditors can trace.

## Description
The CP assessment grid (US-MON-16) gains file upload in two places, both backed by the bank's
**external file-upload service**: per-covenant **evidence attachments** and a CP-level
**Compliance Certificate** panel. In both cases the frontend uploads **through our backend** (consistent
with every other external call being server-side), the backend forwards the file to the external
service via a **resilient** client, and we persist the returned **fileId** (+ filename, uploader,
timestamp). Uploads are **non-mandatory** — a disposition saves with zero attachments.

## Acceptance criteria

### AC1 — Attach evidence to a covenant row
```
Given a covenant row on the CP assessment grid
When I upload one or more files from its Attachments cell
Then the backend forwards each file to the external file-upload service
And persists the returned fileId (+ filename) at CP + covenant level.
```

### AC2 — Attachments are non-mandatory
```
Given a breached covenant I am dispositioning
Then I can save a Waiver or Extension with zero attachments
And attachments never block saving a disposition.
```

### AC3 — List and remove covenant attachments
```
Given a covenant row with attachments
Then each is listed with filename, uploaded-by, and when
And I can remove an attachment.
```

### AC4 — CP-level Compliance Certificate (multi-file)
```
Given the CP assessment screen
Then a CP-level Compliance Certificate panel supports uploading multiple files via the same service
And their fileIds persist at CP-monitoring level, are listed (filename, uploaded-by, when),
    and are removable.
```

### AC5 — Configured, resilient upload; failure never corrupts data
```
Given the file-upload service base URL is configured in application.yml
    (covenant.clients.file-upload; dev → WireMock, test → override) and wrapped in the standard
    timeout + retry + circuit breaker
When an upload fails
Then the UI shows a clear error
And no existing attachment/disposition data is corrupted or lost.
```

### AC6 — File type/size validation
```
Given I choose a file to upload
When the file type or size is not allowed
Then it is rejected with a friendly message before/at upload
And an allowed file uploads normally.
```

### AC7 — File references in cov_-prefixed tables
```
Given files have been uploaded
Then their references are stored in cov_-prefixed tables
    (cov_covenant_attachment, cov_compliance_certificate)
And each row records fileId, filename, uploaded-by, and uploaded-at.
```

## Data / API notes
- New external client **`fileupload/FileUploadClient`** + **`WebClientFileUploadClient`**: multipart
  upload via `MultipartBodyBuilder` / `BodyInserters.fromMultipartData`, wrapped in the standard
  resilience4j operators (timeout → circuit breaker → retry) with graceful failure — the pattern from
  `WebClientNotificationClient`. DTO `dto/FileUploadResult(fileId, fileName, …)`.
- New endpoints (multipart) on the monitoring controller:
  - `POST /api/v1/monitoring/cp/{cpId}/covenants/{appliedId}/attachments` and `DELETE …/{id}`.
  - `POST /api/v1/monitoring/cp/{cpId}/compliance-certificate` (multi-file) and `DELETE …/{id}`.
- New tables (both multi-row, uploads non-mandatory):
  - **`cov_covenant_attachment`** — `cpId, appliedCovenantId, fileId, fileName, uploadedBy, uploadedAt`.
  - **`cov_compliance_certificate`** — `cpId, fileId, fileName, uploadedBy, uploadedAt`.
- Config: `covenant.clients.file-upload.base-url` +
  `resilience4j.{circuitbreaker,retry,timelimiter}.instances.fileUpload` in `application.yml`, with
  `application-dev.yml` / `application-test.yml` overrides; WireMock stub
  `wiremock/mappings/file-upload.json`.
- Frontend uses Mantine core `FileButton` / `FileInput` (no new dependency).

## Dependencies
- US-MON-16 — the grid the Attachments cell and Compliance Certificate panel live on.
- US-MON-18 — the AI suggestion call forwards these fileIds to the AI service.
- Reuses the resilient-client pattern and `ClientsProperties` / `WebClientsConfig` plumbing established
  for the notification and ML clients.

## Out of scope (flag for follow-up)
- In-app document preview/rendering (we store references, not content).
- Virus scanning / DLP of uploaded files (assumed handled by the external file-upload service).
- Mandatory-evidence policies (uploads stay non-mandatory in this story).
