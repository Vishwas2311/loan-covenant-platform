package com.crisil.covenant.dto;

import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.entity.AssessmentThreshold;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentStatus;
import com.crisil.covenant.enums.AssessmentTrigger;
import com.crisil.covenant.formula.FormulaType;
import java.time.Instant;
import java.util.List;

public record AssessmentResponse(
        String id,
        String appliedCovenantId,
        String cpId,
        String covenantName,
        Instant scheduledAt,
        Instant executedAt,
        String dataSnapshotJson,
        String conditionExpression,
        String evaluatedExpression,
        String computedValue,
        String computedValueType,
        AssessmentResult result,
        String errorMessage,
        String assessmentNote,
        String workflowInstanceId,
        String workflowError,
        AssessmentTrigger triggeredBy,
        String triggeredByUser,
        AssessmentStatus status,
        List<Comparison> comparisons) {

    /**
     * US-ASM-21: one decomposed comparison of the assessed condition — the measure (left side),
     * operator, threshold literal (right side), the actual value evaluated against the CP's data,
     * and the comparison's value type, in left-to-right {@code ordinal} order.
     */
    public record Comparison(
            int ordinal,
            String measure,
            String operator,
            String thresholdValue,
            String actualValue,
            FormulaType valueType) {

        static Comparison from(AssessmentThreshold t) {
            return new Comparison(t.getOrdinal(), t.getMeasure(), t.getOperator(),
                    t.getThresholdValue(), t.getActualValue(), t.getValueType());
        }
    }

    public static AssessmentResponse from(Assessment a) {
        return new AssessmentResponse(
                a.getId(),
                a.getAppliedCovenant().getId(),
                a.getAppliedCovenant().getCpId(),
                a.getAppliedCovenant().getCovenantMaster().getName(),
                a.getScheduledAt(),
                a.getExecutedAt(),
                a.getDataSnapshotJson(),
                a.getConditionExpression(),
                a.getEvaluatedExpression(),
                a.getComputedValue(),
                a.getComputedValueType(),
                a.getResult(),
                a.getErrorMessage(),
                a.getAssessmentNote(),
                a.getWorkflowInstanceId(),
                a.getWorkflowError(),
                a.getTriggeredBy(),
                a.getTriggeredByUser(),
                a.getStatus(),
                a.getComparisons().stream().map(Comparison::from).toList());
    }
}
