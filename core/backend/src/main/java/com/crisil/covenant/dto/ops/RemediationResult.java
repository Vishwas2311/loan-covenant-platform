package com.crisil.covenant.dto.ops;

import java.util.List;

/**
 * The outcome of a safe (non-destructive) remediation action — force re-ingest, clear stale
 * ingestion-state, or re-run an assessment. One shape serves all three so the Support UI can render a
 * single result panel; fields not relevant to a given action are empty/null. Coverage-excluded
 * ({@code dto/**}).
 *
 * @param action            {@code reingest}, {@code clear-ingestion-state}, or {@code reassess}
 * @param cpId              the CP the action targeted (for {@code reassess}, the covenant's CP)
 * @param appliedCovenantId the re-assessed applied covenant, or {@code null} for CP-scoped actions
 * @param tablesTouched     the data-source-table ids re-ingested or whose state row was cleared
 * @param ingested          {@code true} when at least one extract was actually issued (re-ingest only)
 * @param assessmentResult  the fresh assessment result name for {@code reassess}, else {@code null}
 * @param warnings          human-readable notes (e.g. a skipped obligor-keyed table with no mapping)
 */
public record RemediationResult(
        String action,
        String cpId,
        String appliedCovenantId,
        List<String> tablesTouched,
        boolean ingested,
        String assessmentResult,
        List<String> warnings) {
}
