package com.crisil.covenant.formula;

/**
 * US-AGG-11: how a windowed aggregation samples the underlying time series before the aggregate
 * function runs.
 *
 * <ul>
 *   <li>{@link #FLAT} — the default/legacy behaviour: aggregate <em>every</em> reading whose as-of
 *       date falls in the rolling window.</li>
 *   <li>{@link #PERIODIC} — first collapse each reporting period in the window to its
 *       <em>period-end</em> figure (the latest reading dated on or before the as-of date within
 *       that period), then aggregate those per-period figures.</li>
 * </ul>
 *
 * <p>Sampling is a per-formula (per-aggregation) choice; it is a no-op for {@code LATEST}/
 * {@code CURRENT} (a single most-recent value has no per-period sense).
 */
public enum SamplingMode {
    FLAT,
    PERIODIC
}
