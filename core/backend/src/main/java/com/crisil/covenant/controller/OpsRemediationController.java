package com.crisil.covenant.controller;

import com.crisil.covenant.dto.ops.ReingestRequest;
import com.crisil.covenant.dto.ops.RemediationResult;
import com.crisil.covenant.service.OpsRemediationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Safe (non-destructive) production-support remediation under the {@code X-Support-Token} gate. Every
 * endpoint writes a {@code cov_ops_audit} row. These fix a stuck covenant — force re-ingest, clear a
 * stale freshness-gate row, or re-run one assessment — without deleting any business data.
 */
@RestController
@RequestMapping("/api/v1/ops")
@Tag(name = "Ops Remediation",
        description = "Safe non-destructive production-support remediation (X-Support-Token gated)")
public class OpsRemediationController {

    private final OpsRemediationService service;

    public OpsRemediationController(OpsRemediationService service) {
        this.service = service;
    }

    @PostMapping("/cps/{cpId}/reingest")
    @Operation(summary = "Force-refresh then re-ingest a CP's source tables",
            description = "Stamps source-changed and re-ingests every distinct table the CP's covenants "
                    + "use (or just the one in the body). Obligor-keyed tables with no mapping are "
                    + "skipped with a warning. Audited.")
    public RemediationResult reingest(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId,
            @RequestBody(required = false) ReingestRequest request) {
        return service.reingestCp(cpId, request == null ? null : request.tableId());
    }

    @DeleteMapping("/cps/{cpId}/ingestion-state")
    @Operation(summary = "Clear the freshness-gate control row(s) for a CP",
            description = "Deletes the cov_ingestion_state row(s) for the CP (optionally one table) so "
                    + "the next assessment re-ingests — the fix for a stale control row masking empty "
                    + "staging. Audited.")
    public RemediationResult clearIngestionState(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId,
            @Parameter(description = "Optional data source table id to scope the clear to")
            @RequestParam(required = false) String tableId) {
        return service.clearIngestionState(cpId, tableId);
    }

    @PostMapping("/applied-covenants/{appliedCovenantId}/reassess")
    @Operation(summary = "Re-run one applied covenant's assessment",
            description = "Executes a fresh MANUAL-triggered assessment for the applied covenant and "
                    + "echoes the new result. Audited.")
    public RemediationResult reassess(
            @Parameter(description = "Applied covenant id") @PathVariable String appliedCovenantId) {
        return service.reassess(appliedCovenantId);
    }
}
