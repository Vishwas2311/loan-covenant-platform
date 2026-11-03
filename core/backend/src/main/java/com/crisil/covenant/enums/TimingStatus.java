package com.crisil.covenant.enums;

/**
 * US-MON-08/09: the derived timing state of an applied covenant relative to today.
 * Single source of truth shared by the per-CP timeline and the portfolio schedule.
 */
public enum TimingStatus {
    /** Monitoring start date is in the future. */
    NOT_STARTED,
    /** Started and the next due date is comfortably in the future. */
    UP_TO_DATE,
    /** Started and the next due date is within the due-soon window (default 7 days). */
    DUE_SOON,
    /** Started and a due date has passed with no assessment for it. */
    OVERDUE,
    /** Monitoring has ended (end date passed). */
    ENDED
}
