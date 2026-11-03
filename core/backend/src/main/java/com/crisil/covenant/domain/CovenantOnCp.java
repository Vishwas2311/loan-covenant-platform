package com.crisil.covenant.domain;

import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.enums.CovenantClassification;
import com.crisil.covenant.enums.CovenantConditionType;
import java.time.Instant;
import java.time.LocalDate;
import com.crisil.covenant.enums.TimingStatus;

public record CovenantOnCp(
        String appliedCovenantId,
        String covenantMasterId,
        String covenantName,
        CovenantCategory category,
        Frequency frequency,
        LocalDate startDate,
        AppliedCovenantStatus status,
        Instant nextRunAt,
        Instant lastRunAt,
        AssessmentResult latestResult,
        Instant latestExecutedAt,
        String latestAssessmentId,
        LocalDate nextDueDate,
        TimingStatus timingStatus,
        long overdueByDays,
        String conditionExpression,
        CovenantConditionType conditionType,
        CovenantClassification classification,
        AssessmentType assessmentType) {
}
