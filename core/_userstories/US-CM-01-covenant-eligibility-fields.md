# US-CM-01 — Covenant eligibility & classification fields

**Epic:** Covenant eligibility, classification & assessment type
**Status:** Draft · **Priority:** High

## Story
**As a** Covenant Administrator
**I want** to capture a covenant's eligibility and classification attributes when I create or edit
a covenant
**so that** the system can automatically recommend the covenant for the credit proposals (CPs) it
actually applies to, instead of relying on business segment alone.

## Description
The Covenant create/edit screen gains a **"Eligibility & classification"** section with the fields
below. These describe *which deals a covenant is meant for*; they are used by the recommendation
engine (US-REC-04) and shown read-only on the covenant detail screen.

| # | Field | Control | Required | Notes |
|---|-------|---------|----------|-------|
| 1 | Islamic Banking | Yes / No (segmented or radio) | **Yes** | Shariah-compliant deals (Yes) vs conventional (No). No "All" option. |
| 2 | Segment | Multi-select + **All** | **Yes** | Business segment — the existing `businessSegments`, now catalog-driven and mandatory. |
| 3 | Industry | Multi-select + **All** | **Yes** | e.g. Infrastructure, Metals, Telecom, Manufacturing… |
| 4 | Country of risk | Multi-select + **All** | **Yes** | ISO country list. |
| 5 | Country of incorporation | Multi-select + **All** | **Yes** | ISO country list. |
| 6 | Credit proposal type | Multi-select + **All** | **Yes** | e.g. Term Loan, Working Capital, Project Finance… |
| 7 | Legal entity type | Multi-select + **All** | No (optional) | e.g. Public Ltd, Private Ltd, LLP, SPV… |
| 8 | Facility type | Multi-select + **All** | No (optional) | e.g. Cash Credit, Term Loan, Guarantee, LC… |
| 9 | Collateral type | Multi-select + **All** | No (optional) | e.g. Property, Plant & Machinery, Receivables, Cash… |

- Dropdown options come from data-driven reference catalogs via API (US-DATA-06), not hard-coded.
- "All" semantics are specified in **US-CM-02**.
- Assessment type (Auto/Manual) is a separate field specified in **US-CM-03**; it lives in the same
  section but is not part of this story's field list.

## Mandatory fields (whole covenant screen)
A covenant cannot be saved until **all** of these have a value — enforced in the UI **and** the
server (HTTP 400 `VALIDATION_ERROR` if bypassed):

1. Covenant name
2. Description *(was optional — now mandatory)*
3. Islamic Banking
4. Segment *(was optional — now mandatory)*
5. Industry
6. Country of risk
7. Country of incorporation
8. Credit proposal type
9. Assessment type (see US-CM-03)
10. Condition — the formula (Auto) or instruction text (Manual); always required (pre-existing)

> **Removed:** the free-text **Covenant type** classifier was dropped from the covenant master.
> `name` (unique) already identifies a covenant, so `type` was redundant; it is no longer stored,
> validated, returned by the API, or shown on the create/edit/detail screens.

Also required (pre-existing, unchanged): **Category** (Financial / Non-financial).

**Optional:** Legal entity type, Facility type, Collateral type.

> For multi-selects the "must have a value" rule is satisfied by **All** (the default) or one or
> more specific values — they are never blank; mandatory ones carry a required marker. The fields
> with real teeth are the text/choice fields (name, description, Islamic Banking, assessment
> type), which must be explicitly provided.

## Scope
**In scope:** the 8 fields above on the covenant create/edit + detail screens; persistence; API
request/response; audit of changes; backward compatibility for existing covenants.
**Out of scope:** the recommendation algorithm (US-REC-04), assessment type behaviour (US-CM-03),
the option catalogs/seed data (US-DATA-06).

## Acceptance criteria

### AC1 — Fields are present on create
```
Given I am creating a new covenant
When the form renders
Then I see an "Eligibility & classification" section containing:
  Islamic Banking (Yes/No), Industry, Country of risk, Country of incorporation,
  Credit proposal type, Legal entity type, Facility type, Collateral type
And each of the 7 list fields is a searchable multi-select that includes an "All" option
And Islamic Banking defaults to "No" and each multi-select defaults to "All".
```

### AC2 — Persistence
```
Given I have filled the name, condition, and eligibility fields
When I save the covenant
Then the eligibility values are persisted with the covenant
And re-opening the covenant shows exactly the values I selected.
```

### AC3 — Edit
```
Given an existing covenant
When I open it in edit mode
Then every eligibility field is pre-populated from the stored values
And I can change any of them and save
And the change is reflected on the detail screen.
```

### AC4 — Mandatory-field validation
```
Given I am saving a covenant
When any mandatory field is missing — name, description, Islamic Banking, Segment, Industry,
     Country of risk, Country of incorporation, Credit proposal type, Assessment type, or Condition
     (also Category) —
Then the form blocks save and shows a per-field required message (e.g. "Description is required")
And each mandatory multi-select is valid when it has either "All" or ≥1 specific value
And the optional dimensions (Legal entity type, Facility type, Collateral type) never block save
And the request is rejected with HTTP 400 (VALIDATION_ERROR) if these rules are bypassed via the API.
```

### AC5 — Detail view
```
Given a saved covenant with eligibility values
When I open the covenant detail screen
Then I see each dimension and its values rendered as chips
And a dimension set to "All" renders the single chip "All".
```

### AC6 — Audit
```
Given an existing covenant applied to one or more CPs
When I change any eligibility field and save
Then the change is recorded in the covenant's change log (old → new, who, when).
```

### AC7 — Backward compatibility
```
Given a covenant created before this feature (no eligibility data)
When it is loaded or matched
Then every multi-select dimension is treated as "All" (wildcard)
And Islamic Banking defaults to "No"
And the covenant continues to load, edit, and assess without error.
```

### AC8 — Dropdown options are API-populated
```
Given the covenant create/edit screen
When it loads
Then every multi-select's options are fetched from the reference-data API (US-DATA-06), not hardcoded
And the dropdowns show a loading state until the options arrive, and an error+retry if the fetch fails
(The "All" option is added by the UI; Islamic Banking and Assessment type remain static controls.)
```

## Data / API notes (non-binding, see US-DATA-06)
- Store the multi-select lists on `covenant_master` as JSON string-lists (mirroring the existing
  `business_segments` via `StringListConverter`); store Islamic Banking as a `BIT`/boolean column.
  No new table is required for the covenant fields themselves; **any new table introduced for this
  epic must use the `cov_` prefix**.
- `CovenantResponse` / `Create`/`UpdateCovenantRequest` gain the 8 fields. A multi-select stored as
  `["ALL"]` (or empty) represents the wildcard.

## Dependencies
- US-DATA-06 (option catalogs) for the dropdown values.
- US-CM-02 ("All" semantics).
