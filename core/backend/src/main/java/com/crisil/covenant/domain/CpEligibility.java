package com.crisil.covenant.domain;

import com.crisil.covenant.domain.CovenantAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * US-REC-04: the credit proposal's eligibility attributes, projected from the Attributes API
 * payload ({@link CovenantAttributes}) into the dimensions the recommendation engine matches on.
 *
 * <p>Segment comes from the top-level {@code businessSegment}; industry prefers an explicit
 * {@code industry}/{@code industries} attribute and falls back to {@code sector}; the remaining
 * dimensions are read from the free-form {@code attributes} map under a small set of accepted key
 * aliases. Multi-valued attributes accept comma- or pipe-separated values. A missing attribute is
 * left empty/blank, which (per the story) fails any non-wildcard covenant dimension.
 */
public record CpEligibility(
        boolean islamicBanking,
        List<String> segments,
        List<String> industries,
        String countryOfRisk,
        String countryOfIncorporation,
        String creditProposalType,
        String legalEntityType,
        List<String> facilityTypes,
        List<String> collateralTypes) {

    public static CpEligibility from(CovenantAttributes attrs) {
        Map<String, String> a = attrs.safeAttributes();
        return new CpEligibility(
                parseBoolean(first(a, "islamicBanking", "islamic", "shariah", "shariahCompliant")),
                single(attrs.businessSegment()),
                industries(a, attrs.sector()),
                first(a, "countryOfRisk", "countryRisk", "riskCountry"),
                first(a, "countryOfIncorporation", "incorporationCountry", "countryIncorporation"),
                first(a, "creditProposalType", "cpType", "proposalType", "loanType"),
                first(a, "legalEntityType", "entityType", "legalType"),
                multi(a, "facilityType", "facilityTypes", "facilities"),
                multi(a, "collateralType", "collateralTypes", "collaterals"));
    }

    private static List<String> industries(Map<String, String> a, String sector) {
        String explicit = first(a, "industry", "industries");
        if (explicit != null && !explicit.isBlank()) {
            return split(explicit);
        }
        return single(sector);
    }

    /** First non-blank value among the given keys, or {@code null}. */
    private static String first(Map<String, String> a, String... keys) {
        for (String key : keys) {
            String value = a.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /** Multi-valued attribute (comma/pipe separated) collected across key aliases. */
    private static List<String> multi(Map<String, String> a, String... keys) {
        for (String key : keys) {
            String value = a.get(key);
            if (value != null && !value.isBlank()) {
                return split(value);
            }
        }
        return List.of();
    }

    private static List<String> split(String value) {
        List<String> out = new ArrayList<>();
        for (String part : value.split("[,|]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static List<String> single(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.trim());
    }

    private static boolean parseBoolean(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes")
                || v.equalsIgnoreCase("y") || v.equals("1");
    }
}
