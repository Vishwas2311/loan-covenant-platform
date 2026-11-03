package com.crisil.covenant.dto;

/**
 * Request body for starting a workflow <em>case</em> against the covenant-monitoring engine:
 * {@code POST /api/runtime/cases} with {@code {"workflowCode":"…","variables":{"manualAssigneee":"…"}}}.
 *
 * <p>{@code manualAssigneee} carries the CP's Relationship-Manager userId. The triple-{@code e}
 * spelling is intentional — it matches the workflow engine's published contract verbatim.
 */
public record WorkflowCaseRequest(String workflowCode, Variables variables) {

    public record Variables(String manualAssigneee) {
    }
}
