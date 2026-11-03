package com.crisil.covenant.controller;

import com.crisil.covenant.dto.ops.OpsAuditView;
import com.crisil.covenant.service.OpsAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read the append-only production-support audit trail under the {@code X-Support-Token} gate. Every
 * WRITE action (re-ingest, clear-ingestion-state, re-assess, destructive cleanup) leaves a row here;
 * this endpoint reads them back newest-first for a target so a reviewer can see who did what, when, and
 * whether it succeeded. Read-only.
 */
@RestController
@RequestMapping("/api/v1/ops")
@Tag(name = "Ops Audit",
        description = "Read the production-support audit trail (X-Support-Token gated)")
public class OpsAuditController {

    private final OpsAuditService service;

    public OpsAuditController(OpsAuditService service) {
        this.service = service;
    }

    @GetMapping("/audit")
    @Operation(summary = "Read the audit trail for a target, newest first",
            description = "Returns every recorded WRITE action for the given targetType (required), "
                    + "optionally narrowed to a single targetId. Read-only.")
    public List<OpsAuditView> auditTrail(
            @Parameter(description = "Target type, e.g. 'cp' or 'applied_covenant' (required)")
            @RequestParam String targetType,
            @Parameter(description = "Target id to narrow to; omit for the whole trail of this type")
            @RequestParam(required = false) String targetId) {
        return service.trail(targetType, targetId);
    }
}
