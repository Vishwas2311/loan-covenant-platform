package com.crisil.covenant.controller;

import com.crisil.covenant.dto.CertificateRequirementRequest;
import com.crisil.covenant.dto.CertificateRequirementView;
import com.crisil.covenant.security.CpAccessGuard;
import com.crisil.covenant.service.CertificateRequirementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-22: compliance-certificate requirements per CP and period, with PENDING / RECEIVED / OVERDUE. */
@RestController
@RequestMapping("/api/v1/monitoring/cp/{cpId}/certificate-requirements")
@Tag(name = "CP assessment", description = "RM covenant review, disposition & status audit")
public class CertificateRequirementController {

    private final CertificateRequirementService service;
    private final CpAccessGuard guard;

    public CertificateRequirementController(CertificateRequirementService service, CpAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping
    @Operation(summary = "FR-22: certificate requirements for a CP with their derived state")
    public List<CertificateRequirementView> list(@PathVariable String cpId) {
        guard.requireCp(cpId);
        return service.forCp(cpId);
    }

    @PutMapping
    @Operation(summary = "FR-22: require a certificate for a period (or change its due date)")
    public CertificateRequirementView upsert(@PathVariable String cpId,
                                             @Valid @RequestBody CertificateRequirementRequest request) {
        guard.requireCp(cpId);
        return service.upsert(cpId, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "FR-22: remove a certificate requirement")
    public ResponseEntity<Void> remove(@PathVariable String cpId, @PathVariable String id) {
        guard.requireCp(cpId);
        service.remove(cpId, id);
        return ResponseEntity.noContent().build();
    }
}
