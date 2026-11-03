package com.crisil.covenant.service;

import com.crisil.covenant.dto.ExtractionRecommendation;
import java.util.List;

/** C1 client: covenants the Document Processing Service extracted from the CP's loan agreements. */
public interface ExtractionRecommender {

    /** Never throws; an unavailable DPS yields an empty list so onboarding continues. */
    List<ExtractionRecommendation> recommend(String cpId);
}
