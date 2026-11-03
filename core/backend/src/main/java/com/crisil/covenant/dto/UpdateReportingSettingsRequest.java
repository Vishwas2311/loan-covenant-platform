package com.crisil.covenant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Update the organisation reporting-calendar policy (US-AGG-11) and the monitoring-schedule windows
 * (US-MON-09). {@code fiscalYearStartMonth} is 1-12 (1 = calendar year, 4 = an April–March fiscal
 * year); {@code periodicIncludesCurrentPeriod} toggles whether a plain {@code PERIODIC} window counts
 * the in-progress period. The two {@code *Days} windows (1-365) drive the portfolio schedule
 * filter buckets: {@code dueSoonDays} = "Due soon", {@code upcomingWindowDays} = the forward
 * look-ahead / "Upcoming". {@code dispositionApprovalRequired} (FR-12) is optional; null keeps the
 * current value.
 */
public record UpdateReportingSettingsRequest(
        @NotNull @Min(1) @Max(12) Integer fiscalYearStartMonth,
        @NotNull Boolean periodicIncludesCurrentPeriod,
        @NotNull @Min(1) @Max(365) Integer dueSoonDays,
        @NotNull @Min(1) @Max(365) Integer upcomingWindowDays,
        Boolean dispositionApprovalRequired,
        @Pattern(regexp = "[A-Z]{3}") String reportingCurrency) {

    public UpdateReportingSettingsRequest(Integer fiscalYearStartMonth, Boolean periodicIncludesCurrentPeriod,
                                          Integer dueSoonDays, Integer upcomingWindowDays,
                                          Boolean dispositionApprovalRequired) {
        this(fiscalYearStartMonth, periodicIncludesCurrentPeriod, dueSoonDays, upcomingWindowDays,
                dispositionApprovalRequired, null);
    }

    public UpdateReportingSettingsRequest(Integer fiscalYearStartMonth, Boolean periodicIncludesCurrentPeriod,
                                          Integer dueSoonDays, Integer upcomingWindowDays) {
        this(fiscalYearStartMonth, periodicIncludesCurrentPeriod, dueSoonDays, upcomingWindowDays, null, null);
    }
}
