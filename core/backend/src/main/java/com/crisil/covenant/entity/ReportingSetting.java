package com.crisil.covenant.entity;

import com.crisil.covenant.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

/**
 * US-AGG-11: the organisation-wide reporting-calendar policy that governs PERIODIC windowed
 * aggregation. A single row holds two levers:
 *
 * <ul>
 *   <li>{@code fiscalYearStartMonth} (1-12) — the first month of the reporting year (1 = calendar
 *       year, 4 = a common April–March fiscal year). Quarters and years are aligned to it.</li>
 *   <li>{@code periodicIncludesCurrentPeriod} — whether a plain {@code PERIODIC} window counts the
 *       in-progress period (the one containing the as-of date). Per-formula
 *       {@code PERIODIC_CURRENT}/{@code PERIODIC_COMPLETED} keywords override this.</li>
 * </ul>
 *
 * <p>US-MON-09: the same singleton also carries the tunable monitoring-schedule windows that drive
 * the portfolio schedule filter buckets — {@code dueSoonDays} ("Due soon") and
 * {@code upcomingWindowDays} (the forward look-ahead / "Upcoming").
 *
 * <p>{@code startingSoonDays} maps the {@code starting_soon_days} column added in V6 but is now
 * <em>retired</em>: the "Starting soon" bucket and its setting were removed from the UI/API. The
 * column and this mapping are retained (kept at their default) only so {@code ddl-auto: validate}
 * still matches the released schema — a portable {@code DROP COLUMN} isn't possible across H2 and
 * SQL Server (the column carries an unnamed default constraint). Do not surface it again.
 *
 * <p>FR-12: {@code dispositionApprovalRequired} turns maker-checker on for Waiver/Extension
 * dispositions (default on).
 *
 * <p>Exactly one row exists (seeded by migration); the service treats it as a singleton.
 */
@Entity
@Table(name = "cov_reporting_setting")
public class ReportingSetting extends AuditableEntity {

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "fiscal_year_start_month", nullable = false)
    private int fiscalYearStartMonth = 1;

    @Column(name = "periodic_includes_current_period", nullable = false)
    private boolean periodicIncludesCurrentPeriod = true;

    @Column(name = "due_soon_days", nullable = false)
    private int dueSoonDays = 7;

    @Column(name = "upcoming_window_days", nullable = false)
    private int upcomingWindowDays = 30;

    @Column(name = "starting_soon_days", nullable = false)
    private int startingSoonDays = 14;

    @Column(name = "disposition_approval_required", nullable = false)
    private boolean dispositionApprovalRequired = true;

    /** FR-23: ISO 4217 code amounts are converted to on screens and in reports. */
    @Column(name = "reporting_currency", nullable = false, length = 3)
    private String reportingCurrency = "INR";

    /** A compliant covenant with headroom under this % of its limit is flagged at risk (0 = off). */
    @Column(name = "at_risk_headroom_percent", nullable = false)
    private int atRiskHeadroomPercent = 10;

    public int getAtRiskHeadroomPercent() {
        return atRiskHeadroomPercent;
    }

    public void setAtRiskHeadroomPercent(int atRiskHeadroomPercent) {
        this.atRiskHeadroomPercent = atRiskHeadroomPercent;
    }

    public String getReportingCurrency() {
        return reportingCurrency;
    }

    public void setReportingCurrency(String reportingCurrency) {
        this.reportingCurrency = reportingCurrency;
    }

    public boolean isDispositionApprovalRequired() {
        return dispositionApprovalRequired;
    }

    public void setDispositionApprovalRequired(boolean dispositionApprovalRequired) {
        this.dispositionApprovalRequired = dispositionApprovalRequired;
    }

    public String getId() {
        return id;
    }

    public int getFiscalYearStartMonth() {
        return fiscalYearStartMonth;
    }

    public void setFiscalYearStartMonth(int fiscalYearStartMonth) {
        this.fiscalYearStartMonth = fiscalYearStartMonth;
    }

    public boolean isPeriodicIncludesCurrentPeriod() {
        return periodicIncludesCurrentPeriod;
    }

    public void setPeriodicIncludesCurrentPeriod(boolean periodicIncludesCurrentPeriod) {
        this.periodicIncludesCurrentPeriod = periodicIncludesCurrentPeriod;
    }

    public int getDueSoonDays() {
        return dueSoonDays;
    }

    public void setDueSoonDays(int dueSoonDays) {
        this.dueSoonDays = dueSoonDays;
    }

    public int getUpcomingWindowDays() {
        return upcomingWindowDays;
    }

    public void setUpcomingWindowDays(int upcomingWindowDays) {
        this.upcomingWindowDays = upcomingWindowDays;
    }

    public int getStartingSoonDays() {
        return startingSoonDays;
    }

    public void setStartingSoonDays(int startingSoonDays) {
        this.startingSoonDays = startingSoonDays;
    }
}
