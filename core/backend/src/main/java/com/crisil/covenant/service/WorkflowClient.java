package com.crisil.covenant.service;

import com.crisil.covenant.domain.WorkflowCase;
import com.crisil.covenant.dto.WorkflowCaseRequest;

/**
 * Client for the covenant-monitoring workflow engine. A workflow <em>case</em> is opened once per CP
 * when assessment starts ({@link #startCase}); its returned {@code caseId} then keys three further
 * downstream calls ({@link #submitCaseStep1}/{@link #submitCaseStep2}/{@link #submitCaseStep3}).
 */
public interface WorkflowClient {

    /**
     * Open a workflow case: {@code POST /api/runtime/cases}. Returns the engine's response carrying the
     * {@code caseId}. Throws {@link com.crisil.covenant.exception.WorkflowException} if the engine is
     * unreachable.
     */
    WorkflowCase startCase(WorkflowCaseRequest request);

    /** Downstream case call 1, keyed by {@code caseId}. TODO(workflow): contract not yet defined. */
    void submitCaseStep1(String caseId);

    /** Downstream case call 2, keyed by {@code caseId}. TODO(workflow): contract not yet defined. */
    void submitCaseStep2(String caseId);

    /** Downstream case call 3, keyed by {@code caseId}. TODO(workflow): contract not yet defined. */
    void submitCaseStep3(String caseId);
}
