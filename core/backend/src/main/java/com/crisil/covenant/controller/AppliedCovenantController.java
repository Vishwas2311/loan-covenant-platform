package com.crisil.covenant.controller;

import com.crisil.covenant.security.CpAccessGuard;
import com.crisil.covenant.entity.AppliedCovenantAudit;
import com.crisil.covenant.dto.AppliedCovenantResponse;
import com.crisil.covenant.dto.BulkApplyCovenantsRequest;
import com.crisil.covenant.dto.DefinitionChangeRow;
import com.crisil.covenant.dto.UpdateAppliedCovenantRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.service.AppliedCovenantService;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Applied Covenants", description = "Covenants applied to a Credit Proposal")
public class AppliedCovenantController {

    private final CpAccessGuard guard;

    private final AppliedCovenantService service;

    public AppliedCovenantController(AppliedCovenantService service,
            CpAccessGuard guard) {
        this.guard = guard;
        this.service = service;
    }

    @PostMapping("/credit-proposals/{cpId}/applied-covenants")
    @Operation(summary = "Apply a set of covenants to a CP with frequencies",
            description = "Bulk-applies covenants to a Credit Proposal, each with its monitoring "
                    + "frequency (and optional custom cron). Returns 201 with the created rows.")
    @ApiResponse(responseCode = "201", description = "Covenants applied",
            content = @Content(mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = AppliedCovenantResponse.class))))
    public ResponseEntity<List<AppliedCovenantResponse>> apply(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId,
            @Valid @RequestBody BulkApplyCovenantsRequest request) {
        guard.requireCp(cpId);
        return ResponseEntity.status(201).body(service.applyBulk(cpId, request));
    }

    @GetMapping("/credit-proposals/{cpId}/applied-covenants")
    @Operation(summary = "List covenants applied to a CP")
    public List<AppliedCovenantResponse> list(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId) {
        guard.requireCp(cpId);
        return service.listForCp(cpId);
    }

    @PatchMapping("/applied-covenants/{id}")
    @Operation(summary = "Update frequency, custom cron, or status (suspend/resume) of an applied covenant",
            description = "Partially updates one applied covenant; each change is written to its "
                    + "frequency/cron/status audit trail.")
    public AppliedCovenantResponse update(
            @Parameter(description = "Applied covenant id") @PathVariable String id,
            @Valid @RequestBody UpdateAppliedCovenantRequest request) {
        guard.requireAppliedCovenant(id);
        return service.update(id, request);
    }

    @GetMapping("/applied-covenants/{id}/audit")
    @Operation(summary = "Frequency / cron / status change history for an applied covenant")
    public List<AppliedCovenantAudit> audit(
            @Parameter(description = "Applied covenant id") @PathVariable String id) {
        guard.requireAppliedCovenant(id);
        return service.auditFor(id);
    }

    @GetMapping("/applied-covenants/{id}/definition-history")
    @Operation(summary = "Per-CP definition change log for an applied covenant",
            description = "Append-only trail of every committed change to this covenant's per-CP "
                    + "condition, newest first. Empty when it was applied without an override.")
    @ApiResponse(responseCode = "200", description = "Definition changes, newest first",
            content = @Content(mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = DefinitionChangeRow.class))))
    public List<DefinitionChangeRow> definitionHistory(
            @Parameter(description = "Applied covenant id") @PathVariable String id) {
        guard.requireAppliedCovenant(id);
        return service.definitionHistory(id);
    }
}
