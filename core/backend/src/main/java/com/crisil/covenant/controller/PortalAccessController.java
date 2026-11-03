package com.crisil.covenant.controller;

import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.security.CpAccessGuard;
import com.crisil.covenant.security.Roles;
import com.crisil.covenant.service.LoanSyncService;
import com.crisil.covenant.service.PortalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** C1 portal access (bank side) and C8 loan-system sync runs (support). */
@RestController
@Tag(name = "Portal access and loan sync", description = "C1 who may use the portal; C8 the nightly loan sync")
public class PortalAccessController {

    public record GrantRequest(@NotBlank @Size(max = 64) String userId) {}

    private final PortalService portal;
    private final LoanSyncService sync;
    private final CpAccessGuard guard;

    public PortalAccessController(PortalService portal, LoanSyncService sync, CpAccessGuard guard) {
        this.portal = portal;
        this.sync = sync;
        this.guard = guard;
    }

    private static void requireBankWriter() {
        if (!CurrentUserContext.hasAnyRole(Roles.RM, Roles.APPROVER, Roles.ADMIN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the CP's RM or approver may change portal access");
        }
    }

    @GetMapping("/api/v1/monitoring/cp/{cpId}/portal-access")
    @Operation(summary = "Borrower users who may use the portal for this CP")
    public List<PortalService.AccessView> access(@PathVariable String cpId) {
        guard.requireCp(cpId);
        return portal.accessFor(cpId);
    }

    @PostMapping("/api/v1/monitoring/cp/{cpId}/portal-access")
    @Operation(summary = "Give a borrower user portal access to this CP")
    public List<PortalService.AccessView> grant(@PathVariable String cpId, @RequestBody GrantRequest request) {
        guard.requireCp(cpId);
        requireBankWriter();
        return portal.grant(cpId, request.userId(), CurrentUserContext.currentUser().orElse("system"));
    }

    @DeleteMapping("/api/v1/monitoring/cp/{cpId}/portal-access/{userId}")
    @Operation(summary = "Remove a borrower user's portal access to this CP")
    public List<PortalService.AccessView> revoke(@PathVariable String cpId, @PathVariable String userId) {
        guard.requireCp(cpId);
        requireBankWriter();
        return portal.revoke(cpId, userId);
    }

    @GetMapping("/api/v1/ops/loan-sync-runs")
    @Operation(summary = "The last 20 loan-system sync runs and what each changed")
    public List<LoanSyncService.RunView> syncRuns() {
        return sync.recent();
    }

    @PostMapping("/api/v1/ops/loan-sync-runs")
    @Operation(summary = "Check every CP against the loan system now (also runs nightly)")
    public LoanSyncService.RunView runSync() {
        return sync.run(CurrentUserContext.currentUser().orElse("system"));
    }
}
