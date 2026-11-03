package com.crisil.covenant.dto;

import java.util.List;

public record CovenantUsageResponse(
        String covenantId,
        long totalApplied,
        long activelyApplied,
        List<String> sampleCpIds) {
}
