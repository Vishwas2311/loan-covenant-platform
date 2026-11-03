package com.crisil.covenant.dto;

import com.crisil.covenant.entity.ReportingSetting;
import java.time.Instant;

/**
 * The organisation reporting-calendar policy (US-AGG-11) plus the tunable monitoring-schedule
 * windows (US-MON-09) and the disposition maker-checker switch (FR-12) as returned by the settings API. Carries the levers plus who last changed them.
 */
public record ReportingSettingsResponse(
        int fiscalYearStartMonth,
        boolean periodicIncludesCurrentPeriod,
        int dueSoonDays,
        int upcomingWindowDays,
        boolean dispositionApprovalRequired,
        String reportingCurrency,
        Instant modifiedDate,
        String modifiedBy) {

    public static ReportingSettingsResponse from(ReportingSetting s) {
        return new ReportingSettingsResponse(
                s.getFiscalYearStartMonth(),
                s.isPeriodicIncludesCurrentPeriod(),
                s.getDueSoonDays(),
                s.getUpcomingWindowDays(),
                s.isDispositionApprovalRequired(),
                s.getReportingCurrency(),
                s.getModifiedDate(),
                s.getModifiedBy());
    }

    /** The built-in defaults used when no settings row has been persisted yet. */
    public static ReportingSettingsResponse defaults() {
        return new ReportingSettingsResponse(1, true, 7, 30, true, "INR", null, null);
    }
}
