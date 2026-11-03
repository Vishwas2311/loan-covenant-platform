# US-REC-04 — Recommendation matching by eligibility criteria

**Epic:** Covenant eligibility, classification & assessment type
**Status:** Draft · **Priority:** High

## Story
**As a** Relationship Manager onboarding a credit proposal
**I want** the system to recommend covenants by matching the CP's attributes against each covenant's
eligibility criteria
**so that** I only see covenants that are actually relevant to this deal, and I can trust the
recommendations instead of hand-picking from the whole library.

## Description
When recommendations are requested for a CP, the engine compares the CP's attributes to every active
covenant's eligibility (US-CM-01) and returns the covenants that fit. A covenant fits a CP when
**all** of its dimensions match (logical **AND** across dimensions); within a multi-select dimension,
a match means **the CP's value(s) intersect the covenant's set** (membership / **OR** within the
dimension). A dimension set to **All** is a wildcard and is skipped.

### Matching rule (per covenant, given a CP)
A covenant is recommended ⇔ **every** dimension below passes:

| Dimension | Passes when |
|-----------|-------------|
| Islamic Banking | `covenant.islamicBanking == cp.islamicBanking` (exact; no wildcard) |
| Segment | covenant is **All**, OR `covenant.segments ∩ cp.segments ≠ ∅` |
| Industry | covenant is **All**, OR `covenant.industries ∩ cp.industries ≠ ∅` |
| Country of risk | covenant is **All**, OR `cp.countryOfRisk ∈ covenant.countriesOfRisk` |
| Country of incorporation | covenant is **All**, OR `cp.countryOfIncorporation ∈ covenant.countriesOfIncorporation` |
| Credit proposal type | covenant is **All**, OR `cp.creditProposalType ∈ covenant.creditProposalTypes` |
| Legal entity type | covenant is **All**, OR `cp.legalEntityType ∈ covenant.legalEntityTypes` |
| Facility type | covenant is **All**, OR `covenant.facilityTypes ∩ cp.facilityTypes ≠ ∅` |
| Collateral type | covenant is **All**, OR `covenant.collateralTypes ∩ cp.collateralTypes ≠ ∅` |

Notes:
- Some CP attributes are single-valued (country of risk/incorporation, CP type, legal entity type)
  and some are multi-valued (industries, facility types, collateral types) — both use the
  intersection/membership test above.
- A covenant whose every multi-select is **All** and whose Islamic flag matches is recommended for
  every CP (subject to Islamic Banking).

## Acceptance criteria

### AC1 — Match on all dimensions
```
Given a covenant with Islamic=No, Industry={Infrastructure}, Country of risk={IN}, all other
      dimensions = All
And a CP with Islamic=No, industries include Infrastructure, country of risk = IN
When recommendations are computed for the CP
Then the covenant is recommended
And the match reason explains the matched dimensions.
```

### AC2 — Excluded by one dimension
```
Given the same covenant (Country of risk = {IN})
And a CP whose country of risk = AE
When recommendations are computed
Then the covenant is NOT recommended (one dimension failed → AND fails).
```

### AC3 — All = wildcard is skipped
```
Given a covenant with Industry = All
And any CP regardless of industry
When recommendations are computed
Then the Industry dimension never causes exclusion.
```

### AC4 — Islamic banking is an exact match
```
Given a covenant with Islamic Banking = Yes
And a CP with Islamic Banking = No
When recommendations are computed
Then the covenant is NOT recommended (Islamic banking mismatch)
And vice-versa.
```

### AC5 — Intersection within multi-valued dimensions
```
Given a covenant with Facility type = {Term Loan, Guarantee}
And a CP whose facilities = {Cash Credit, Guarantee}
When recommendations are computed
Then the Facility-type dimension passes (intersection = {Guarantee}).
```

### AC6 — Match reasons
```
Given a recommended covenant
Then the recommendation carries a human-readable reason listing the dimensions that matched
     (e.g. "Industry: Infrastructure; Country of risk: IN; Facility: Guarantee")
And wildcard dimensions are not listed as reasons.
```

### AC7 — No eligible covenants
```
Given a CP for which no active covenant's eligibility matches
When recommendations are computed
Then an empty recommendation list is returned (the onboarding wizard then shows
     "No recommendations — add from library").
```

### AC8 — Inactive covenants excluded
```
Given an inactive covenant whose eligibility would match a CP
When recommendations are computed
Then it is not recommended.
```

### AC9 — Backward compatibility
```
Given a covenant created before this feature (no eligibility data)
When recommendations are computed
Then all its multi-select dimensions are treated as All (wildcard)
And only Islamic Banking (default No) participates
So it behaves as a broadly-applicable covenant rather than disappearing.
```

## Dependencies
- **CP attribute source (blocking):** the credit-proposal profile / Data API must expose the CP's
  Islamic-banking flag, industry/industries, country of risk, country of incorporation, credit
  proposal type, legal entity type, facility type(s), and collateral type(s). If an attribute is
  unavailable for a CP, treat it as non-matching for non-wildcard dimensions and surface a
  data-quality note. *This dependency must be confirmed/provisioned.*
- US-CM-01 (covenant eligibility fields), US-CM-02 (All semantics), US-DATA-06 (option catalogs).

## Notes
- Matching should be efficient for the full active library (a few hundred covenants); an in-memory
  filter over active covenants is acceptable initially. Revisit indexing if the library grows large.
