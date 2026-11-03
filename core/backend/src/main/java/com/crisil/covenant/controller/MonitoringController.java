package com.crisil.covenant.controller;

import com.crisil.covenant.security.CpAccessGuard;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.dto.AssessmentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.domain.CovenantOnCp;
import com.crisil.covenant.domain.CpMonitoringSummary;
import com.crisil.covenant.domain.MonitoringEvent;
import com.crisil.covenant.enums.TimingStatus;
import com.crisil.covenant.service.MonitoringService;

@RestController
@RequestMapping("/api/v1/monitoring")
@Tag(name = "Monitoring", description = "Cross-CP monitoring dashboard")
public class MonitoringController {

    private final CpAccessGuard guard;

    private final MonitoringService service;

    public MonitoringController(MonitoringService service,
            CpAccessGuard guard) {
        this.guard = guard;
        this.service = service;
    }

    @GetMapping("/summary")
    @Operation(summary = "Per-CP summary counts of compliant/breached/error/pending covenants",
            description = "Dashboard roll-up: one row per Credit Proposal with counts of applied "
                    + "covenants by latest status.")
    public List<CpMonitoringSummary> summary() {
        return service.summary(CurrentUserContext.currentUser().orElse(null));
    }

    @GetMapping("/cps/{cpId}/covenants")
    @Operation(summary = "Detail view of every covenant applied to a CP",
            description = "Lists each applied covenant for the CP with its effective expression, "
                    + "latest assessment and timing.")
    public List<CovenantOnCp> covenantsForCp(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId) {
        return service.covenantsForCp(cpId, CurrentUserContext.currentUser().orElse(null));
    }

    @GetMapping("/schedule")
    @Operation(summary = "US-MON-09: time-ordered monitoring events across all CPs "
            + "(overdue + the given date window), filterable by status bucket, CP, and search",
            description = "Returns the monitoring agenda: overdue items plus events within [from, to], "
                    + "optionally narrowed by timing bucket, CP and a free-text query.")
    public List<MonitoringEvent> schedule(
            @Parameter(description = "Window start (inclusive), ISO date")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Window end (inclusive), ISO date")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Filter by timing bucket (OVERDUE / DUE_SOON / UPCOMING …)")
            @RequestParam(required = false) TimingStatus status,
            @Parameter(description = "Filter to a single Credit Proposal id")
            @RequestParam(required = false) String cpId,
            @Parameter(description = "Free-text search across CP and covenant")
            @RequestParam(required = false) String q) {
        return service.schedule(from, to, status, cpId, q,
                CurrentUserContext.currentUser().orElse(null));
    }

    @GetMapping("/applied-covenants/{appliedId}/history")
    @Operation(summary = "Paginated assessment history for an applied covenant",
            description = "Returns a page of past assessments for one applied covenant, newest first.")
    public List<AssessmentResponse> history(
            @Parameter(description = "Applied covenant id") @PathVariable String appliedId,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "25") int size) {
        guard.requireAppliedCovenant(appliedId);
        List<Assessment> assessments = service.historyFor(appliedId, page, size);
        return assessments.stream().map(AssessmentResponse::from).toList();
    }
}
