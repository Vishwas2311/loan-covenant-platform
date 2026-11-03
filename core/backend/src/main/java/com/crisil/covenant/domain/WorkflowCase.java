package com.crisil.covenant.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The workflow engine's response to {@code POST /api/runtime/cases}. Only the {@code caseId} is
 * consumed — it is stored on every {@code cov_assessment} row for the CP's run (in the existing
 * {@code workflow_instance_id} column) and keys the downstream case steps. Unknown JSON properties
 * are ignored so the engine contract can evolve independently.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowCase(String caseId) {
}
