package com.crisil.covenant.dto.ops;

import java.time.Instant;

/**
 * The "why did this specific assessment fail" trace: the stored outcome of one assessment (echoing
 * its persisted {@code errorMessage} verbatim) alongside a fresh, read-only re-diagnosis of the same
 * applied covenant, so an engineer can compare what the assessment recorded against what the pipeline
 * looks like now. Coverage-excluded ({@code dto/**}).
 *
 * @param assessmentId      the assessment id
 * @param appliedCovenantId the applied covenant it ran for
 * @param cpId              the CP the applied covenant belongs to
 * @param result            the stored result (e.g. {@code ERROR}, {@code BREACHED}, {@code COMPLIANT})
 * @param status            the stored status (e.g. {@code COMPLETED})
 * @param errorMessage      the stored error message verbatim (e.g. {@code Missing values for measures:
 *                          [...]}), or {@code null} when the assessment did not error
 * @param executedAt        when the assessment executed, or {@code null} if never executed
 * @param diagnostic        the current read-only diagnosis of the applied covenant's terms
 */
public record AssessmentDiagnosticsResponse(
        String assessmentId,
        String appliedCovenantId,
        String cpId,
        String result,
        String status,
        String errorMessage,
        Instant executedAt,
        CovenantDiagnostic diagnostic) {
}
