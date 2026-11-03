package com.crisil.covenant.formula;

/**
 * US-AGG-11 (Lever 3): whether a {@link SamplingMode#PERIODIC} window counts the <em>in-progress</em>
 * period (the reporting period that contains the as-of date, when the as-of date is before that
 * period's end).
 *
 * <ul>
 *   <li>{@link #DEFAULT} — follow the organisation policy
 *       ({@code periodicIncludesCurrentPeriod}).</li>
 *   <li>{@link #INCLUDE_CURRENT} — force include: the newest of the N periods is the as-of date's
 *       own period.</li>
 *   <li>{@link #COMPLETED_ONLY} — force completed-only: the newest of the N periods is the most
 *       recent whose end is on or before the as-of date.</li>
 * </ul>
 *
 * <p>Only relevant for {@code PERIODIC}; ignored for {@link SamplingMode#FLAT}.
 */
public enum PeriodInclusion {
    DEFAULT,
    INCLUDE_CURRENT,
    COMPLETED_ONLY
}
