# US-DATA-06 — Reference data catalogs & `cov_` table naming

**Epic:** Covenant eligibility, classification & assessment type
**Status:** Superseded · **Priority:** High (foundational — do first)

> **SUPERSEDED (2026-07).** The single `cov_reference_data` table and its `GET /api/v1/reference-data`
> endpoint have been **retired**. Eligibility dropdown catalogs are now sourced per-category directly from
> external **master-data APIs** by the frontend (`frontend/src/api/masterData.ts`,
> `frontend/src/config/masterData.ts`), with bundled offline fixtures for dev/test
> (`frontend/src/api/masterDataFixtures.ts`). The `cov_` table-naming convention defined below still applies
> to all other tables. The reference-data design described in the rest of this story is retained for
> historical context only.

## Story
**As a** Developer / Administrator
**I want** the eligibility dropdown options to come from data-driven reference catalogs, and all new
tables to follow a consistent naming convention
**so that** the option lists can change without code edits, and the schema stays consistent and
recognisable.

## Description
The seven multi-selects (US-CM-01) are populated from reference catalogs rather than hard-coded
enums. Provide a single, category-keyed reference table and an API to read it. Establish and enforce
the **`cov_` table-naming convention** for every new table.

### Reference data
A new table **`cov_reference_data`** holds option catalogs keyed by category:

| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT IDENTITY | PK |
| category | VARCHAR(64) | e.g. `SEGMENT`, `INDUSTRY`, `COUNTRY`, `CREDIT_PROPOSAL_TYPE`, `LEGAL_ENTITY_TYPE`, `FACILITY_TYPE`, `COLLATERAL_TYPE` |
| code | VARCHAR(64) | stable value stored on covenants (e.g. `IN`, `INFRASTRUCTURE`) |
| label | VARCHAR(255) | display text |
| sort_order | INT | display ordering |
| active | BIT | soft-disable an option |

- `Country of risk` and `Country of incorporation` share the `COUNTRY` category.
- Unique on `(category, code)`. Seed with sensible defaults (full ISO country list; a starter set of
  industries/CP types/entity types/facility types/collateral types) in a dev seed migration.
- Reference data is **prod** schema (table) + may be seeded in prod via a baseline migration; the
  large/illustrative seed rows go in the dev demo location.

### Options API (all dropdowns are API-populated)
**Every eligibility dropdown is populated from this API — the frontend hardcodes no option lists.**
Two read shapes:
- **Batch:** `GET /api/v1/reference-data` → all active options grouped by category
  (`{ "INDUSTRY": [{code,label}], "COUNTRY": [...], ... }`). The covenant form calls this **once**
  on load to fill every dropdown.
- **Single category:** `GET /api/v1/reference-data?category=FACILITY_TYPE` → that category's active
  options, ordered by `sort_order`.

The synthetic **All** option (US-CM-02) is **not** in the API response — the UI adds it. Options are
cacheable (e.g. React Query with a long stale time); the form shows a loading state while fetching
and a clear error state if the call fails.

### Naming convention
**Every new table is prefixed `cov_`** (e.g. `cov_reference_data`; if eligibility is later normalised
into join tables, `cov_covenant_industry`, etc.). Existing tables are **not** renamed
(`covenant_master`, `measure`, `applied_covenant`, …). This is documented in the project `CLAUDE.md`.

## Acceptance criteria

### AC1 — Reference table exists and is seeded
```
Given the database after migration
Then a table named cov_reference_data exists with (category, code, label, sort_order, active)
And it is uniquely keyed on (category, code)
And it is seeded with options for every category used by the covenant screen.
```

### AC2 — Options API (batch + per-category)
```
Given the reference data is seeded
When the UI calls GET /api/v1/reference-data (batch)
Then it receives all active options grouped by category, each ordered by sort_order
And when it calls GET /api/v1/reference-data?category=FACILITY_TYPE
Then it receives only that category's active options, ordered by sort_order
And the covenant multi-selects render those options (plus the client-added synthetic "All").
```

### AC2b — All dropdowns are API-populated (no hardcoded lists)
```
Given the covenant create/edit screen
When it loads
Then it populates every eligibility dropdown from the reference-data API (one batch call)
And no eligibility option list is hardcoded in the frontend
And while the call is in flight the dropdowns show a loading state (and are not yet editable)
And if the call fails the screen shows a clear error with a retry, rather than empty dropdowns.
```

### AC3 — Covenants store codes, UI shows labels
```
Given a covenant saved with Industry = {Infrastructure}
Then the stored value is the option code (e.g. INFRASTRUCTURE)
And the UI resolves codes to labels for display
And an unknown/retired code still displays gracefully (shows the code).
```

### AC4 — Naming convention enforced & documented
```
Given any new table introduced by this epic (or later)
Then its name starts with cov_
And the convention is stated in the project CLAUDE.md
And no pre-existing table is renamed.
```

### AC5 — Soft-disable an option
```
Given an option is set active = false
When the options API is queried
Then that option is omitted from the dropdowns
But covenants that already reference its code keep displaying it (AC3).
```

## Notes / decisions
- **Single table vs per-category tables:** a single `cov_reference_data` with a `category` column is
  recommended (fewer tables, one API). Per-category tables are also valid but each must be `cov_`.
- **Storage of selections on the covenant:** JSON string-lists of codes on `covenant_master`
  (mirroring `business_segments`) keep it simple and require no new join tables; normalised
  `cov_covenant_<dimension>` join tables are an option if reporting needs SQL-level filtering — both
  honour the `cov_` prefix.

## Dependencies
- None (foundational). Blocks US-CM-01 (dropdowns) and US-REC-04 (codes used in matching).
