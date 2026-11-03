package com.crisil.covenant.controller;

import com.crisil.covenant.dto.ops.AssessmentDiagnosticsResponse;
import com.crisil.covenant.dto.ops.CpDiagnosticsResponse;
import com.crisil.covenant.dto.ops.IngestionStateView;
import com.crisil.covenant.dto.ops.StagingInspectResponse;
import com.crisil.covenant.service.OpsDiagnosticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only production-support diagnostics under the {@code X-Support-Token} gate. These endpoints
 * explain <em>why</em> a covenant assessment would (or did) fail without writing or re-ingesting
 * anything — the flagship "resolve the ticket in 5 minutes" surface. All mutation lives on the
 * remediation / cleanup controllers.
 */
@RestController
@RequestMapping("/api/v1/ops")
@Tag(name = "Ops Diagnostics",
        description = "Read-only production-support diagnostics (X-Support-Token gated)")
public class OpsDiagnosticsController {

    private final OpsDiagnosticsService service;

    public OpsDiagnosticsController(OpsDiagnosticsService service) {
        this.service = service;
    }

    @GetMapping("/cps/{cpId}/diagnostics")
    @Operation(summary = "Diagnose why a CP's covenants resolve or fail",
            description = "Re-walks the assessment read path for every covenant applied to the CP and "
                    + "classifies each formula term with a DiagnosticReason (missing measure, no obligor "
                    + "mapping, staging empty with stale ingestion-state, item code not staged, window "
                    + "empty, or OK). Writes nothing.")
    public CpDiagnosticsResponse diagnoseCp(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId) {
        return service.diagnoseCp(cpId);
    }

    @GetMapping("/assessments/{assessmentId}/diagnostics")
    @Operation(summary = "Explain one assessment's stored outcome plus a fresh re-diagnosis",
            description = "Echoes the assessment's persisted result/errorMessage and re-diagnoses its "
                    + "applied covenant against the current pipeline state.")
    public AssessmentDiagnosticsResponse diagnoseAssessment(
            @Parameter(description = "Assessment id") @PathVariable String assessmentId) {
        return service.diagnoseAssessment(assessmentId);
    }

    @GetMapping("/cps/{cpId}/staging")
    @Operation(summary = "Inspect the raw staged rows for a (CP, table) pair",
            description = "Returns the staged rows an assessment would read for the CP under the given "
                    + "table, resolved at the same entity key (CP id, or the CP's obligor for an "
                    + "obligor-keyed table).")
    public StagingInspectResponse inspectStaging(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId,
            @Parameter(description = "Data source table id") @RequestParam String tableId) {
        return service.inspectStaging(cpId, tableId);
    }

    @GetMapping("/cps/{cpId}/ingestion-state")
    @Operation(summary = "Show the freshness-gate control rows for a CP",
            description = "Lists the cov_ingestion_state rows (and the gate's current ingest/skip "
                    + "verdict) for every table the CP's covenants use — the surface for spotting a "
                    + "control row that claims fresh while staging is empty.")
    public List<IngestionStateView> ingestionState(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId) {
        return service.ingestionStates(cpId);
    }
}
