package com.crisil.covenant.dto;

import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.Frequency;
import jakarta.validation.constraints.Size;

public record UpdateAppliedCovenantRequest(
        Frequency frequency,
        @Size(max = 64) String cronExpression,
        AppliedCovenantStatus status) {
}
