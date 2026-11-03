package com.crisil.covenant.dto;

import com.crisil.covenant.enums.AssessmentResult;
import java.time.LocalDate;
import java.util.List;

/**
 * FR-24: a CP's covenants side by side over its last assessment dates (newest first). A cell is null
 * when the covenant was not assessed that day. FR-26: {@code trend} compares the newest and oldest numeric
 * actuals shown (advisory; null when fewer than two numbers).
 */
public record PeriodComparison(String cpId, List<LocalDate> periods, List<Row> rows) {

    public record Row(String appliedCovenantId, String covenantName, String conditionExpression, List<Cell> cells,
                      Trend trend) {
    }

    public enum Trend { IMPROVING, STABLE, WORSENING }

    public record Cell(AssessmentResult result, String computedValue, String assessmentId) {
    }
}
