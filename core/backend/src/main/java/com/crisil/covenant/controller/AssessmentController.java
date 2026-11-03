package com.crisil.covenant.controller;

import com.crisil.covenant.security.CpAccessGuard;
import com.crisil.covenant.dto.AssessmentResponse;
import com.crisil.covenant.dto.CompleteManualAssessmentRequest;
import com.crisil.covenant.config.CurrentUserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.enums.AssessmentTrigger;
import com.crisil.covenant.service.AssessmentService;

@RestController
@RequestMapping("/api/v1/assessments")
@Tag(name = "Assessments", description = "Covenant assessment runs")
public class AssessmentController {

    private final CpAccessGuard guard;

    private final AssessmentService service;

    public AssessmentController(AssessmentService service,
            CpAccessGuard guard) {
        this.guard = guard;
        this.service = service;
    }

    @PostMapping("/run")
    @Operation(summary = "Manually trigger an assessment for an applied covenant",
            description = "Runs the formula engine now for one applied covenant and appends the result "
                    + "(attributed to the current user). Financial covenants complete automatically; "
                    + "non-financial ones return a PENDING_REVIEW assessment to be completed manually.")
    public AssessmentResponse runManual(
            @Parameter(description = "Applied covenant id to assess") @RequestParam String appliedCovenantId) {
        guard.requireAppliedCovenant(appliedCovenantId);
        String user = CurrentUserContext.currentUser().orElse(null);
        return AssessmentResponse.from(
                service.runAssessment(appliedCovenantId, AssessmentTrigger.MANUAL, user));
    }

    @GetMapping
    @Operation(summary = "List assessments for a CP",
            description = "Returns every assessment recorded for the given Credit Proposal, newest first.")
    public List<AssessmentResponse> listForCp(
            @Parameter(description = "Credit Proposal id (CP_ID)") @RequestParam String cpId) {
        guard.requireCp(cpId);
        return service.listForCp(cpId).stream().map(AssessmentResponse::from).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an assessment by id (with snapshot + resolved expression)")
    public AssessmentResponse get(@Parameter(description = "Assessment id") @PathVariable String id) {
        guard.requireAssessment(id);
        return AssessmentResponse.from(service.get(id));
    }

    @PostMapping("/{id}/complete-manual")
    @Operation(summary = "Record the outcome of a pending manual assessment (result + required note)",
            description = "Completes a PENDING_REVIEW assessment with a COMPLIANT/BREACHED/NA result "
                    + "and a mandatory note; attributed to the current user.")
    public AssessmentResponse completeManual(
            @Parameter(description = "Assessment id") @PathVariable String id,
            @Valid @RequestBody CompleteManualAssessmentRequest request) {
        guard.requireAssessment(id);
        String user = CurrentUserContext.currentUser().orElse(null);
        return AssessmentResponse.from(
                service.completeManual(id, request.result(), request.note(), user));
    }
}
