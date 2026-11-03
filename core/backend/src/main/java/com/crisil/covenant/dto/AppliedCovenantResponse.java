package com.crisil.covenant.dto;

import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.enums.ThresholdSource;
import com.crisil.covenant.enums.CovenantClassification;
import com.crisil.covenant.enums.CovenantConditionType;
import java.time.LocalDate;

public record AppliedCovenantResponse(
        String id,
        String cpId,
        String covenantMasterId,
        String covenantName,
        Frequency frequency,
        String cronExpression,
        LocalDate startDate,
        LocalDate endDate,
        AppliedCovenantStatus status,
        String snapshotJson,
        String conditionOverride,
        String effectiveCondition,
        CovenantConditionType conditionType,
        CovenantClassification classification,
        ThresholdSource thresholdSource) {

    public static AppliedCovenantResponse from(AppliedCovenant a) {
        String override = a.getConditionExpression();
        String effective = override != null && !override.isBlank()
                ? override
                : a.getCovenantMaster().getConditionExpression();
        return new AppliedCovenantResponse(
                a.getId(),
                a.getCpId(),
                a.getCovenantMaster().getId(),
                a.getCovenantMaster().getName(),
                a.getFrequency(),
                a.getCronExpression(),
                a.getStartDate(),
                a.getEndDate(),
                a.getStatus(),
                a.getSnapshotJson(),
                override,
                effective,
                a.getConditionType(),
                a.getClassification(),
                a.getThresholdSource());
    }
}
