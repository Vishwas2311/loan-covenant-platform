package com.crisil.covenant.domain;

import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.enums.AssessmentResult;
import java.time.Instant;
import java.time.LocalDate;
import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.enums.TimingStatus;

/**
 * US-MON-09: one monitoring event in the portfolio schedule — an applied covenant on a CP with its
 * derived timing. {@code cpName} is nullable (the CP display name is not stored locally; the UI
 * falls back to {@code cpId}).
 *
 * <p>US-MON-21 adds the effective {@code dispositionStatus} (and {@code extensionDate} for an
 * EXTENSION) so the By-Covenant agenda can badge handled events. Both are {@code null} unless the
 * covenant's latest breach carries an <em>active</em> Waiver/Extension; the {@code timingStatus} and
 * {@code nextDueDate} are unaffected — a disposition is a compliance outcome, not a reschedule.
 */
public record MonitoringEvent(
        String cpId,
        String cpName,
        String appliedCovenantId,
        String covenantMasterId,
        String covenantName,
        Frequency frequency,
        LocalDate startDate,
        LocalDate nextDueDate,
        Instant lastAssessedAt,
        AssessmentResult lastResult,
        String latestAssessmentId,
        TimingStatus timingStatus,
        long overdueByDays,
        CovenantDispositionStatus dispositionStatus,
        LocalDate extensionDate) {
}
