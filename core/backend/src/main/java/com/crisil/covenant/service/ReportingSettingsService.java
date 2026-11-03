package com.crisil.covenant.service;

import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.dto.ReportingSettingsResponse;
import com.crisil.covenant.dto.UpdateReportingSettingsRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.crisil.covenant.entity.ReportingSetting;
import com.crisil.covenant.repository.ReportingSettingRepository;

/**
 * US-AGG-11: reads and updates the singleton organisation reporting-calendar policy. The row is
 * seeded by migration; if it is somehow absent {@link #current()} falls back to the built-in
 * defaults (calendar year, include the in-progress period) so assessments never fail for a missing
 * setting.
 */
@Service
@Transactional(readOnly = true)
public class ReportingSettingsService {

    private final ReportingSettingRepository repository;

    public ReportingSettingsService(ReportingSettingRepository repository) {
        this.repository = repository;
    }

    /** FR-23: the organisation's reporting currency (default INR). */
    public String reportingCurrency() {
        return repository.findAll().stream().findFirst()
                .map(ReportingSetting::getReportingCurrency)
                .orElse("INR");
    }

    /** FR-12: whether Waiver/Extension dispositions need an approver (default true). */
    public boolean dispositionApprovalRequired() {
        return repository.findAll().stream().findFirst()
                .map(ReportingSetting::isDispositionApprovalRequired)
                .orElse(true);
    }

    /** The current organisation policy (defaults if no row has been persisted). */
    public ReportingSettingsResponse current() {
        return repository.findAll().stream().findFirst()
                .map(ReportingSettingsResponse::from)
                .orElseGet(ReportingSettingsResponse::defaults);
    }

    /** Update the singleton policy; validates the fiscal-year start month is 1-12. */
    @Transactional
    public ReportingSettingsResponse update(UpdateReportingSettingsRequest request) {
        int month = request.fiscalYearStartMonth();
        if (month < 1 || month > 12) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FISCAL_MONTH",
                    "fiscalYearStartMonth must be between 1 and 12");
        }
        ReportingSetting setting = repository.findAll().stream().findFirst()
                .orElseGet(ReportingSetting::new);
        setting.setFiscalYearStartMonth(month);
        setting.setPeriodicIncludesCurrentPeriod(request.periodicIncludesCurrentPeriod());
        setting.setDueSoonDays(request.dueSoonDays());
        setting.setUpcomingWindowDays(request.upcomingWindowDays());
        if (request.dispositionApprovalRequired() != null) {
            setting.setDispositionApprovalRequired(request.dispositionApprovalRequired());
        }
        if (request.reportingCurrency() != null) {
            setting.setReportingCurrency(request.reportingCurrency());
        }
        return ReportingSettingsResponse.from(repository.save(setting));
    }
}
