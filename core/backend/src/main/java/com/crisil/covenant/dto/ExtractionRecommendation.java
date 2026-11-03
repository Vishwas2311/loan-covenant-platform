package com.crisil.covenant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * C1: one covenant proposed from a loan agreement by the Document Processing Service
 * (contracts/dps-api.yaml {@code Recommendation}). {@code covenantMasterId == null} means the term did
 * not match the library and is offered as a CP-scoped custom covenant ({@link #customCovenant}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractionRecommendation(
        String covenantMasterId,
        String recommendedExpression,
        Double confidence,
        String rationale,
        String frequency,
        Source source,
        CustomCovenant customCovenant) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String documentId, String fileId, Integer page, String quote) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CustomCovenant(String name, String definition, String assessmentType) {
    }
}
