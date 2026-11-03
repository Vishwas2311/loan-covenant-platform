package com.crisil.covenant.util;

import com.crisil.covenant.enums.RecommendationStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RecommendationStatusConverter extends LowerCaseEnumConverter<RecommendationStatus> {
    public RecommendationStatusConverter() {
        super(RecommendationStatus.class);
    }
}
