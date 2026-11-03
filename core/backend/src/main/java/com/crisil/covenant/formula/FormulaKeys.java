package com.crisil.covenant.formula;

/** Canonical value-map keys for measure/aggregation leaves, shared by evaluator/renderer/engine. */
final class FormulaKeys {

    private FormulaKeys() {
    }

    static String key(Node.MeasureRef ref) {
        return ref.name();
    }

    static String key(Node.Aggregation agg) {
        String base = agg.function().name() + "(" + agg.measure().name() + "," + agg.window().canonical();
        SamplingKeyword sampling = SamplingKeyword.of(agg.sampling(), agg.inclusion());
        // FLAT keeps the legacy 2-arg key so existing formulas/snapshots are byte-identical; a
        // PERIODIC variant appends its keyword so it is a distinct resolvable term.
        return sampling == SamplingKeyword.FLAT
                ? base + ")"
                : base + "," + sampling.name() + ")";
    }
}
