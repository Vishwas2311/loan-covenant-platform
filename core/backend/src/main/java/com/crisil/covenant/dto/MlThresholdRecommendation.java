package com.crisil.covenant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * One ML-recommended threshold for a covenant: the full recommended condition expression (same
 * measures as the current one, only threshold values differ), a confidence score and a one-line
 * rationale. Returned by the ML service and, for changed+valid rows, surfaced to the UI.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlThresholdRecommendation(
        String covenantMasterId,
        String recommendedExpression,
        BigDecimal confidence,
        String rationale) {
}
