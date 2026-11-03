package com.crisil.covenant.domain;

/**
 * One By-CP roll-up row for the monitoring workspace (US-MON-15). {@code breached} stays a pure
 * {@code cov_assessment} count (the daily run / US-MON-14 email are untouched); {@code dispositioned}
 * (US-MON-20) additionally reports how many of those breaches carry an <em>active</em> Waiver/Extension
 * so the row can read "Breached 4 (2 handled)" without hiding the breach signal.
 */
public record CpMonitoringSummary(
        String cpId,
        long applied,
        long compliant,
        long breached,
        long error,
        long pendingReview,
        long dispositioned) {
}
