package com.crisil.covenant.formula;

/**
 * A resolvable unit of a formula: either a bare measure ({@code aggregation == null},
 * {@code window == null}) read at the as-of date, or an aggregation over a window. The
 * {@code valueKey} is the map key under which the resolver must place the computed value and under
 * which the evaluator/renderer look it up — keeping both sides in lock-step. {@code sampling} +
 * {@code inclusion} carry the US-AGG-11 period-aware sampling choice (FLAT/DEFAULT for bare measures
 * and legacy aggregations).
 */
public record FormulaTerm(String measure, FunctionKind aggregation, Window window,
                          SamplingMode sampling, PeriodInclusion inclusion, String valueKey) {

    /** Convenience constructor for a FLAT term (bare measure or legacy aggregation). */
    public FormulaTerm(String measure, FunctionKind aggregation, Window window, String valueKey) {
        this(measure, aggregation, window, SamplingMode.FLAT, PeriodInclusion.DEFAULT, valueKey);
    }

    public boolean isAggregation() {
        return aggregation != null;
    }
}
