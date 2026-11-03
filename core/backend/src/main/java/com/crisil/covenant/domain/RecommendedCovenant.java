package com.crisil.covenant.domain;

import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.entity.CovenantMaster;
import java.util.List;

public record RecommendedCovenant(
        String covenantId,
        String name,
        CovenantCategory category,
        String description,
        List<String> businessSegments,
        String matchReason,
        Frequency defaultFrequency,
        String defaultCron,
        boolean custom) {

    public static RecommendedCovenant from(CovenantMaster cm, String matchReason) {
        return new RecommendedCovenant(
                cm.getId(),
                cm.getName(),
                cm.getCategory(),
                cm.getDescription(),
                List.copyOf(cm.getBusinessSegments()),
                matchReason,
                cm.getDefaultFrequency(),
                cm.getDefaultCron(),
                cm.isCustom());
    }
}
