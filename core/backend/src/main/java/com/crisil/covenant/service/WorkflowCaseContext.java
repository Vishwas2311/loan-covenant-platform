package com.crisil.covenant.service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Per-run holder that memoises the workflow-case outcome for each CP, so a CP with N covenants opens
 * exactly <b>one</b> case per run. Both success and failure are cached — a CP whose case creation
 * failed is not retried for every covenant in the same run.
 *
 * <p>"Run" scope: the daily breach sweep shares one context across all CPs it touches; an isolated
 * per-covenant Quartz fire or a manual trigger each get their own context (hence their own case).
 *
 * <p>Not thread-safe — a run is processed sequentially.
 */
public final class WorkflowCaseContext {

    private final Map<String, CaseOutcome> byCp = new HashMap<>();

    /** Return the CP's memoised outcome, invoking {@code creator} exactly once on first request. */
    public CaseOutcome computeIfAbsent(String cpId, Supplier<CaseOutcome> creator) {
        return byCp.computeIfAbsent(cpId, key -> creator.get());
    }

    /**
     * The result of opening (or failing to open) a workflow case for one CP: exactly one of
     * {@code caseId} (success) or {@code error} (failure) is non-null. Persisted onto every
     * {@code cov_assessment} row for the CP as {@code workflow_instance_id} / {@code workflow_error}.
     */
    public record CaseOutcome(String caseId, String error) {

        public static CaseOutcome ok(String caseId) {
            return new CaseOutcome(caseId, null);
        }

        public static CaseOutcome error(String message) {
            return new CaseOutcome(null, message);
        }
    }
}
