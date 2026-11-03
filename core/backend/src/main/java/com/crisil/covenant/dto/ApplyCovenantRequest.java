package com.crisil.covenant.dto;

import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.enums.ThresholdSource;
import com.crisil.covenant.enums.CovenantClassification;
import com.crisil.covenant.enums.CovenantConditionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * One covenant to apply to a CP. US-FREQ-07: {@code startDate} is the per-CP monitoring start date
 * (defaults to today when omitted; must be today or later). {@code frequency}/{@code cronExpression}
 * are the per-CP cadence. {@code conditionType} (precedent/subsequent) and {@code classification}
 * (external/internal) are CP-level attributes captured per applied covenant; both are optional.
 * US-ML-13: {@code thresholdSource} records how the applied threshold was set — {@code USER} (a
 * manual edit) or {@code ML} (an accepted ML recommendation); optional and only meaningful when a
 * {@code conditionExpression} override is present (no override ⇒ persisted as {@code MASTER}).
 */
public record ApplyCovenantRequest(
        @NotNull String covenantMasterId,
        @NotNull Frequency frequency,
        @Size(max = 64) String cronExpression,
        @Size(max = 2000) String conditionExpression,
        LocalDate startDate,
        CovenantConditionType conditionType,
        CovenantClassification classification,
        ThresholdSource thresholdSource) {

    /** Backward-compatible constructor (pre US-FREQ-07): start date defaults to today at apply time. */
    public ApplyCovenantRequest(String covenantMasterId, Frequency frequency,
                                String cronExpression, String conditionExpression) {
        this(covenantMasterId, frequency, cronExpression, conditionExpression, null, null, null, null);
    }

    /** Constructor without condition type / classification (keeps US-FREQ-07 callers compiling). */
    public ApplyCovenantRequest(String covenantMasterId, Frequency frequency,
                                String cronExpression, String conditionExpression,
                                LocalDate startDate) {
        this(covenantMasterId, frequency, cronExpression, conditionExpression, startDate, null, null,
                null);
    }

    /** Constructor without threshold source (keeps pre US-ML-13 callers compiling). */
    public ApplyCovenantRequest(String covenantMasterId, Frequency frequency,
                                String cronExpression, String conditionExpression,
                                LocalDate startDate, CovenantConditionType conditionType,
                                CovenantClassification classification) {
        this(covenantMasterId, frequency, cronExpression, conditionExpression, startDate,
                conditionType, classification, null);
    }
}
