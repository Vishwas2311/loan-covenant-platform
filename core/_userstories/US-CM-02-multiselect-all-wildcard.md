# US-CM-02 — "All" wildcard semantics for multi-selects

**Epic:** Covenant eligibility, classification & assessment type
**Status:** Draft · **Priority:** High

## Story
**As a** Covenant Administrator
**I want** every eligibility multi-select to offer an **All** option that makes the dimension a
wildcard
**so that** I can say "this covenant applies regardless of <dimension>" without enumerating every
possible value, and so the recommendation engine ignores dimensions I don't care about.

## Description
Each of the seven multi-selects in US-CM-01 (Industry, Country of risk, Country of incorporation,
Credit proposal type, Legal entity type, Facility type, Collateral type) includes a synthetic
**All** option at the top of the list. **All** means the covenant matches *any* value on that
dimension, and that dimension is therefore **excluded from recommendation matching** (US-REC-04).

"All" is mutually exclusive with specific values within the same dropdown:
- Selecting **All** clears any specific selections.
- Selecting a specific value clears **All**.
- Clearing the last specific value falls back to **All** (a dimension is never "empty/none").

> Islamic Banking is **not** a multi-select and therefore has **no** "All" option — it is always a
> concrete Yes/No (see US-CM-01 and the open question in the epic README).

## Acceptance criteria

### AC1 — All is offered and is the default
```
Given any eligibility multi-select on the covenant form
Then "All" appears as the first option
And when no explicit choice has been made the field shows "All".
```

### AC2 — Selecting All clears specifics
```
Given a multi-select with one or more specific values selected
When I select "All"
Then all specific values are removed and only "All" remains selected.
```

### AC3 — Selecting a specific value clears All
```
Given a multi-select showing "All"
When I select a specific value (e.g. "Infrastructure")
Then "All" is removed and only the specific value(s) remain selected.
```

### AC4 — Removing the last specific value reverts to All
```
Given a multi-select with exactly one specific value selected
When I remove that value
Then the field reverts to "All" (it is never left empty).
```

### AC5 — Persistence of the wildcard
```
Given a dimension set to "All"
When I save the covenant
Then it is persisted as the wildcard sentinel (e.g. ["ALL"])
And re-opening the covenant shows "All" for that dimension.
```

### AC6 — All is ignored in matching
```
Given a covenant whose "Country of risk" is "All"
And two CPs with different countries of risk
When recommendations are computed for each CP
Then the "Country of risk" dimension does not exclude either CP
And the covenant can be recommended to both (subject to the other dimensions).
```
(The full matching rule lives in US-REC-04; this AC only asserts the wildcard contribution.)

### AC7 — Display
```
Given a covenant detail screen
When a dimension is the wildcard
Then it renders a single "All" chip (not a list of every value).
```

### AC8 — A specific value plus All cannot both persist
```
Given any save path (UI or direct API)
When a dimension contains both "ALL" and specific values
Then the server normalises it to "ALL" (wildcard wins) — it never stores a mixed set.
```

## Design notes (UI/UX)
- Represent **All** visually distinctly (e.g. a filled "All" chip vs outlined value chips) so the
  wildcard state is unmistakable.
- Use the platform multi-select with a pinned top "All" item; enforce the mutual-exclusion rules in
  the field's `onChange` so the two states can never coexist on screen.

## Dependencies
- US-CM-01 (the fields), US-REC-04 (matching consumes the wildcard).
