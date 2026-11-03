package com.crisil.covenant.dto;

import com.crisil.covenant.enums.CovenantDispositionStatus;

/**
 * One per-covenant AI recommendation (US-MON-18 AC2): the recommended disposition status with an
 * optional rationale and confidence. Deserialised from the AI service and returned to the frontend
 * unchanged; applying one is a normal disposition write with source {@code AI}. Coverage-excluded
 * ({@code dto/**}).
 */
public record AiStatusRecommendation(
        String appliedCovenantId,
        CovenantDispositionStatus recommendedStatus,
        String rationale,
        Double confidence) {
}
