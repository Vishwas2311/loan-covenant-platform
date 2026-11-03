package com.crisil.covenant.service;

import com.crisil.covenant.dto.MlThresholdRecommendation;
import com.crisil.covenant.dto.MlThresholdRequest;
import java.util.List;

/**
 * Abstraction over the external ML threshold service. Implementations must degrade gracefully: when
 * the service is slow, erroring, or unavailable they return an empty list so onboarding can proceed
 * (US-ML-12 AC6).
 */
public interface MlThresholdRecommender {

    List<MlThresholdRecommendation> recommend(MlThresholdRequest request);
}
