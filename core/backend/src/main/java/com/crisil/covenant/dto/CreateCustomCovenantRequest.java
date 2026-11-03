package com.crisil.covenant.dto;

import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.Frequency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create a CP-scoped custom covenant. Only these fields are captured at the front end; the remaining
 * covenant-master fields are copied from the CP's attributes (eligibility + Islamic banking) or
 * defaulted. Condition type and classification are CP-level attributes captured per applied covenant at
 * apply time (not on the master), and the monitoring start date is likewise an apply-time (wizard-row)
 * value, so neither is part of this request.
 *
 * <p>US-CM-24: {@code assessmentType} decides how {@code definition} is treated — {@code AUTO} means it
 * is a measure formula (validated, measures extracted, auto-evaluated), {@code MANUAL} means it is a
 * free-text instruction stored verbatim. The category is derived from it (AUTO → Financial,
 * MANUAL → Non-financial) and so is not part of this request.
 */
public record CreateCustomCovenantRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull Frequency frequency,
        @Size(max = 64) String cronExpression,
        @NotBlank @Size(max = 2000) String definition,
        @NotNull AssessmentType assessmentType) {

    /** Defensive default mirroring {@link CreateCovenantRequest#safeAssessmentType()}. */
    public AssessmentType safeAssessmentType() {
        return assessmentType == null ? AssessmentType.AUTO : assessmentType;
    }
}
