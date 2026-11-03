package com.crisil.covenant.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Inbound request from the onboarding wizard: the in-scope covenants (selected, non-manual,
 * not-yet-applied) with their current effective threshold expressions. The backend enriches this
 * with CP details before calling the ML service.
 */
public record MlRecommendationRequest(
        @NotNull @Valid List<MlCovenantContext> items) {
}
