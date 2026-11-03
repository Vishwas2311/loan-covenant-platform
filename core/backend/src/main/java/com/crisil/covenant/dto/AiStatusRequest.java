package com.crisil.covenant.dto;

import com.crisil.covenant.enums.AssessmentResult;
import java.util.List;

/**
 * Body posted to the external AI status-suggestion service (US-MON-18 AC1): the CP's covenants with
 * their expression, latest actual value and machine result, plus the uploaded {@code fileId}s
 * (per-covenant evidence attachments and CP-level compliance certificates) the AI may reason over.
 * Coverage-excluded ({@code dto/**}).
 */
public record AiStatusRequest(
        String cpId,
        List<String> certificateFileIds,
        List<Covenant> covenants) {

    public record Covenant(
            String appliedCovenantId,
            String covenantName,
            String covenantDescription,
            String conditionExpression,
            String computedValue,
            AssessmentResult latestResult,
            List<String> attachmentFileIds) {
    }
}
