package com.crisil.covenant.dto;

import java.util.List;

/**
 * Wire response from the external AI status-suggestion service: the list of per-covenant
 * recommendations. Deserialised verbatim from the service contract. Coverage-excluded
 * ({@code dto/**}).
 */
public record AiSuggestionResponse(List<AiStatusRecommendation> recommendations) {
}
