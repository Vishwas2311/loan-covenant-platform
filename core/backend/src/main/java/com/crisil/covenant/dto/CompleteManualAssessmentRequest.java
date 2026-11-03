package com.crisil.covenant.dto;

import com.crisil.covenant.enums.AssessmentResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * US-ASM-05: an analyst's recorded outcome for a {@code PENDING_MANUAL} assessment. The note
 * (rationale/evidence) is mandatory; the result is constrained to a real outcome by the service
 * ({@code COMPLIANT}, {@code BREACHED}, or {@code NA}).
 */
public record CompleteManualAssessmentRequest(
        @NotNull AssessmentResult result,
        @NotBlank @Size(max = 2000) String note) {
}
