package com.crisil.covenant.enums;

/**
 * The RM-facing status of one covenant on one CP (US-MON-16), derived from the stored assessment
 * result and optionally overlaid with a disposition:
 * <ul>
 *   <li>{@link #COMPLIED} — latest result is COMPLIANT or NA (locked, non-editable);</li>
 *   <li>{@link #BREACHED} — latest result is BREACHED (dispositionable);</li>
 *   <li>{@link #WAIVER} — the RM waived the breach;</li>
 *   <li>{@link #EXTENSION} — the RM granted an extension (requires an extension date).</li>
 * </ul>
 * WAIVER/EXTENSION are dispositions layered on a BREACHED row; they never mutate
 * {@code cov_assessment} or the US-MON-14 dashboard counts.
 */
public enum CovenantDispositionStatus {
    COMPLIED,
    BREACHED,
    WAIVER,
    EXTENSION
}
