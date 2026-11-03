package com.crisil.covenant.controller;

import com.crisil.covenant.security.CpAccessGuard;
import com.crisil.covenant.dto.CpCycleView;
import com.crisil.covenant.dto.CycleReviewResponse;
import com.crisil.covenant.service.CpCycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only cycle-history viewer for linked CPs (US-MON-23): lists the opened CP's renewal lineage as
 * cycles and serves each cycle CP's assessment data as a strictly view-only mirror of the US-MON-16
 * grid + US-MON-17 certificates. No disposition/AI/upload/remove is exposed here; the cycle-detail
 * endpoint is guarded so only CPs in the opened CP's lineage can be read.
 */
@RestController
@RequestMapping("/api/v1/monitoring")
@Tag(name = "CP cycle history", description = "US-MON-23 read-only lineage viewer for linked CPs")
public class CpCycleController {

    private final CpAccessGuard guard;

    private final CpCycleService cycleService;

    public CpCycleController(CpCycleService cycleService,
            CpAccessGuard guard) {
        this.guard = guard;
        this.cycleService = cycleService;
    }

    @GetMapping("/cp/{cpId}/cycles")
    @Operation(summary = "US-MON-23: the CP's renewal lineage as cycles (Cycle 1 = oldest root, "
            + "the opened CP is the highest cycle flagged current)",
            description = "Walks parent CP links from the opened CP up to the root and numbers each as "
                    + "a cycle. A CP with no parent returns a single (current) cycle.")
    public List<CpCycleView> cycles(
            @Parameter(description = "Opened Credit Proposal id (CP_ID)") @PathVariable String cpId) {
        guard.requireCp(cpId);
        return cycleService.cyclesFor(cpId);
    }

    @GetMapping("/cp/{cpId}/cycles/{cycleCpId}")
    @Operation(summary = "US-MON-23: read-only assessment view of one cycle CP within the opened CP's "
            + "lineage — the US-MON-16 review rows + US-MON-17 certificates",
            description = "Returns the same per-covenant review rows and CP compliance certificates the "
                    + "live grid shows, tagged with the cycle number and best-effort CP name. Guarded by "
                    + "lineage membership: a cycleCpId outside the opened CP's lineage yields 404.")
    public CycleReviewResponse cycleReview(
            @Parameter(description = "Opened Credit Proposal id (CP_ID)") @PathVariable String cpId,
            @Parameter(description = "Cycle CP id to view (must be in the opened CP's lineage)")
            @PathVariable String cycleCpId) {
        guard.requireCp(cpId);
        return cycleService.cycleReview(cpId, cycleCpId);
    }
}
