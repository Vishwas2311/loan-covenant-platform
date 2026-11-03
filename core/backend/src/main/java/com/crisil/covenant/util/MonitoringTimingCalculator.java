package com.crisil.covenant.util;

import com.crisil.covenant.enums.Frequency;
import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.TimeZone;
import org.quartz.CronExpression;
import com.crisil.covenant.domain.MonitoringTiming;
import com.crisil.covenant.enums.TimingStatus;

/**
 * US-MON-08/09: computes the derived monitoring timing (next due date + status) for an applied
 * covenant from its cron, start/end dates, and the most recent assessment. Pure and deterministic
 * given a {@code today} and {@code zone}, so both the per-CP timeline and the portfolio schedule
 * share one definition of "next due" / "overdue" / "due soon".
 */
public final class MonitoringTimingCalculator {

    /** Default "due soon" horizon in days (US-MON-08 AC3), used when no org setting is supplied. */
    public static final int DUE_SOON_DAYS = 7;

    private MonitoringTimingCalculator() {
    }

    /**
     * Resolve the effective cron for an applied covenant: the stored per-CP cron when present,
     * otherwise the cron implied by its frequency. Returns {@code null} for CUSTOM with no cron.
     */
    public static String effectiveCron(Frequency frequency, String storedCron) {
        if (storedCron != null && !storedCron.isBlank()) {
            return storedCron;
        }
        return frequency == null ? null : frequency.defaultCron();
    }

    /**
     * Overload that uses the built-in {@link #DUE_SOON_DAYS} horizon. Retained for callers that do
     * not care about the org-configured window (e.g. the daily breach run's due detection).
     */
    public static MonitoringTiming compute(String cron, LocalDate startDate, LocalDate endDate,
            Instant lastAssessedAt, LocalDate today, ZoneId zone) {
        return compute(cron, startDate, endDate, lastAssessedAt, today, zone, DUE_SOON_DAYS);
    }

    /**
     * Compute the derived timing for an applied covenant.
     *
     * @param dueSoonDays the "due soon" horizon in days — a started covenant whose next due date is
     *                    within this many days from {@code today} is {@code DUE_SOON} (org-configured
     *                    via the reporting-settings policy; falls back to {@link #DUE_SOON_DAYS}).
     */
    public static MonitoringTiming compute(String cron, LocalDate startDate, LocalDate endDate,
            Instant lastAssessedAt, LocalDate today, ZoneId zone, int dueSoonDays) {
        int horizon = dueSoonDays > 0 ? dueSoonDays : DUE_SOON_DAYS;
        if (endDate != null && endDate.isBefore(today)) {
            return new MonitoringTiming(null, TimingStatus.ENDED, 0);
        }
        boolean started = startDate != null && !startDate.isAfter(today);
        LocalDate nextDue = nextDueDate(cron, startDate, lastAssessedAt, zone);
        if (nextDue != null && endDate != null && nextDue.isAfter(endDate)) {
            nextDue = null;
        }
        if (!started) {
            return new MonitoringTiming(nextDue, TimingStatus.NOT_STARTED, 0);
        }
        if (nextDue == null) {
            return new MonitoringTiming(null, TimingStatus.UP_TO_DATE, 0);
        }
        if (nextDue.isBefore(today)) {
            return new MonitoringTiming(nextDue, TimingStatus.OVERDUE,
                    ChronoUnit.DAYS.between(nextDue, today));
        }
        if (!nextDue.isAfter(today.plusDays(horizon))) {
            return new MonitoringTiming(nextDue, TimingStatus.DUE_SOON, 0);
        }
        return new MonitoringTiming(nextDue, TimingStatus.UP_TO_DATE, 0);
    }

    /**
     * US-MON-21 (extension defers monitoring): fold an active EXTENSION's grace date into an already
     * computed timing. The effective next monitoring date becomes the {@code extensionDate}, so an
     * overdue breach that has been granted an extension is no longer flagged overdue until the grace
     * date. The one exception is the regular cadence: if the frequency schedule would bring the
     * covenant due again on a <em>future</em> date that is <em>earlier</em> than the extension, that
     * earlier date wins (we never defer past the next real occurrence). {@code status} and
     * {@code overdueByDays} are re-derived from the effective date against {@code today}.
     *
     * <p>A {@code null} extension — or a covenant that has ENDED / not yet started — is returned
     * unchanged (an extension only applies to a started, breached covenant).
     */
    public static MonitoringTiming withExtension(MonitoringTiming base, LocalDate extensionDate,
            LocalDate today, int dueSoonDays) {
        if (extensionDate == null
                || base.status() == TimingStatus.ENDED
                || base.status() == TimingStatus.NOT_STARTED) {
            return base;
        }
        int horizon = dueSoonDays > 0 ? dueSoonDays : DUE_SOON_DAYS;
        LocalDate freqNext = base.nextDueDate();
        // Only a *future* frequency occurrence can pre-empt the grace date; a past (overdue) one is
        // exactly what the extension is deferring, so it must not win.
        boolean frequencyPreempts = freqNext != null
                && !freqNext.isBefore(today)
                && freqNext.isBefore(extensionDate);
        LocalDate effective = frequencyPreempts ? freqNext : extensionDate;
        if (effective.isBefore(today)) {
            return new MonitoringTiming(effective, TimingStatus.OVERDUE,
                    ChronoUnit.DAYS.between(effective, today));
        }
        if (!effective.isAfter(today.plusDays(horizon))) {
            return new MonitoringTiming(effective, TimingStatus.DUE_SOON, 0);
        }
        return new MonitoringTiming(effective, TimingStatus.UP_TO_DATE, 0);
    }

    /**
     * The next monitoring date that must be assessed: the first cron occurrence strictly after the
     * last assessment, or — when never assessed — the first occurrence at/after the start date.
     */
    private static LocalDate nextDueDate(String cron, LocalDate startDate, Instant lastAssessedAt,
            ZoneId zone) {
        if (cron == null || cron.isBlank()) {
            return null;
        }
        CronExpression expr;
        try {
            expr = new CronExpression(cron);
        } catch (ParseException e) {
            return null;
        }
        expr.setTimeZone(TimeZone.getTimeZone(zone));
        Instant base;
        if (lastAssessedAt != null) {
            base = lastAssessedAt;
        } else if (startDate != null) {
            base = startDate.atStartOfDay(zone).toInstant().minusMillis(1);
        } else {
            base = Instant.EPOCH;
        }
        Date next = expr.getNextValidTimeAfter(Date.from(base));
        if (next == null) {
            return null;
        }
        return next.toInstant().atZone(zone).toLocalDate();
    }
}
