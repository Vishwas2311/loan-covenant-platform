package com.crisil.covenant.service;

import com.crisil.covenant.dto.AiStatusRecommendation;
import com.crisil.covenant.dto.AiStatusRequest;
import java.util.List;

/**
 * Asks the external AI service for a recommended disposition status per covenant (US-MON-18).
 * Implementations must never throw — any failure yields an empty list so the modal shows a graceful
 * "AI unavailable" message with no data change (US-MON-18 AC6).
 */
public interface AiStatusRecommender {

    List<AiStatusRecommendation> recommend(AiStatusRequest request);
}
