package com.crisil.covenant.dto;

/**
 * US-DS-13: outcome of one due-driven pre-stage sweep. The sweep coalesces every due-soon covenant's
 * demand into one candidate per {@code (data_source_table, entity)}, then runs the freshness gate on
 * each. Returned to the manual trigger and logged by the Quartz job.
 *
 * @param candidateTargets distinct {@code (table, entity)} pairs the coalescer produced from due covenants
 * @param ingested         candidates the gate decided to extract (staged copy missing / stale / changed)
 * @param skipped          candidates the gate found fresh (no extract issued)
 * @param failed           candidates whose extract threw and were isolated (retried on a later sweep)
 */
public record PreStageResult(int candidateTargets, int ingested, int skipped, int failed) {
}
