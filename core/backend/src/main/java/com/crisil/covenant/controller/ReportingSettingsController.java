package com.crisil.covenant.controller;

import com.crisil.covenant.dto.ReportingSettingsResponse;
import com.crisil.covenant.dto.UpdateReportingSettingsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.crisil.covenant.service.ReportingSettingsService;

/**
 * US-AGG-11: reads and updates the organisation reporting-calendar policy that governs PERIODIC
 * windowed aggregation (fiscal-year start month + whether the in-progress period counts).
 */
@RestController
@RequestMapping("/api/v1/settings/reporting")
@Tag(name = "Reporting Settings",
        description = "Organisation reporting-calendar policy for PERIODIC aggregation")
public class ReportingSettingsController {

    private final ReportingSettingsService service;

    public ReportingSettingsController(ReportingSettingsService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Get the organisation reporting-calendar policy",
            description = "Returns the current fiscal-year start month and whether the in-progress "
                    + "period is counted in PERIODIC windowed aggregation.")
    public ReportingSettingsResponse get() {
        return service.current();
    }

    @PutMapping
    @Operation(summary = "Update the organisation reporting-calendar policy",
            description = "Updates the fiscal-year start month and in-progress-period policy used by "
                    + "PERIODIC windowed aggregation.")
    public ReportingSettingsResponse update(@Valid @RequestBody UpdateReportingSettingsRequest request) {
        return service.update(request);
    }
}
