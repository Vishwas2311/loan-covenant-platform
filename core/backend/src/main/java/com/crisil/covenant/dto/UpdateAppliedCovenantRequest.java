package com.crisil.covenant.dto;

import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.Frequency;
import jakarta.validation.constraints.Size;

/**
 * A partial change to one CP's covenant. {@code conditionExpression} replaces its rule for this CP
 * (for example after an amendment); an empty string goes back to the library rule. {@code reason}
 * is recorded with a rule change.
 */
public record UpdateAppliedCovenantRequest(
        Frequency frequency,
        @Size(max = 64) String cronExpression,
        AppliedCovenantStatus status,
        @Size(max = 2000) String conditionExpression,
        @Size(max = 500) String reason) {

    public UpdateAppliedCovenantRequest(Frequency frequency, String cronExpression, AppliedCovenantStatus status) {
        this(frequency, cronExpression, status, null, null);
    }
}
