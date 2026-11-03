package com.crisil.covenant.controller;

import com.crisil.covenant.dto.ops.CpCleanupRequest;
import com.crisil.covenant.dto.ops.CpCleanupResult;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.service.CpDataCleanupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Destructive production-support cleanup under the {@code X-Support-Token} gate. The single endpoint
 * purges every trace of a CP in one atomic, audited transaction. It is guarded by a
 * type-the-id confirmation: the request body's {@code confirmCpId} must exactly equal the path
 * {@code cpId}, otherwise the request is rejected with {@code 400 CP_CONFIRM_MISMATCH} before any row
 * is touched.
 */
@RestController
@RequestMapping("/api/v1/ops")
@Tag(name = "Ops Cleanup",
        description = "Destructive CP data cleanup (X-Support-Token gated, confirm-to-delete)")
public class OpsCleanupController {

    private final CpDataCleanupService service;

    public OpsCleanupController(CpDataCleanupService service) {
        this.service = service;
    }

    @DeleteMapping("/cps/{cpId}/data")
    @Operation(summary = "Delete ALL data for a CP (destructive, confirm-to-delete)",
            description = "Atomically purges the CP's assessments and their thresholds, monitoring "
                    + "schedules, applied covenants and every CP-keyed satellite (dispositions, audits, "
                    + "attachments, certificates, staging, freshness state, recommendations, CP cache, "
                    + "obligor map). Obligor-grain staging shared with sibling CPs is skipped with a "
                    + "warning. The body's confirmCpId must equal the path cpId. Audited.")
    public CpCleanupResult cleanup(
            @Parameter(description = "Credit Proposal id (CP_ID) whose data will be deleted")
            @PathVariable String cpId,
            @RequestBody(required = false) CpCleanupRequest request) {
        String confirmCpId = request == null ? null : request.confirmCpId();
        if (confirmCpId == null || !confirmCpId.equals(cpId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CP_CONFIRM_MISMATCH",
                    "confirmCpId must exactly match the path cpId to delete a CP's data.");
        }
        return service.cleanupCp(cpId);
    }
}
