package com.crisil.covenant.domain;

import java.time.LocalDate;
import com.crisil.covenant.enums.TimingStatus;

/**
 * US-MON-08/09 derived timing for an applied covenant.
 *
 * @param nextDueDate the next scheduled monitoring date that must be assessed — in the past when
 *                    {@link #status()} is {@code OVERDUE}, in the future otherwise, or {@code null}
 *                    when monitoring has ended / there is no cron.
 * @param status      the derived timing badge.
 * @param overdueByDays whole days the {@link #nextDueDate()} is overdue (0 unless OVERDUE).
 */
public record MonitoringTiming(LocalDate nextDueDate, TimingStatus status, long overdueByDays) {
}
