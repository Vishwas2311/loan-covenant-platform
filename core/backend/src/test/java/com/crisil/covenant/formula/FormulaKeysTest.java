package com.crisil.covenant.formula;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * US-AGG-11: the canonical value-map key for an aggregation must stay byte-identical for FLAT
 * sampling (so existing formulas/snapshots keep resolving) and gain a distinct keyword suffix per
 * PERIODIC variant (so a PERIODIC term is a separate resolvable value from its FLAT sibling).
 */
class FormulaKeysTest {

    private static Node.Aggregation agg(FunctionKind fn, SamplingMode mode, PeriodInclusion inclusion) {
        Node.MeasureRef pat = new Node.MeasureRef("PAT", 0, 3);
        return new Node.Aggregation(fn, pat, new Window(2, WindowUnit.Q), mode, inclusion, 0, 20);
    }

    @Test
    void flatKeyIsTheLegacyTwoArgumentForm() {
        String key = FormulaKeys.key(agg(FunctionKind.AVG, SamplingMode.FLAT, PeriodInclusion.DEFAULT));
        assertThat(key).isEqualTo("AVG(PAT,2q)");
    }

    @Test
    void flatKeyIgnoresInclusion() {
        // FLAT drops inclusion, so a stray non-DEFAULT inclusion still yields the legacy 2-arg key.
        String key = FormulaKeys.key(agg(FunctionKind.AVG, SamplingMode.FLAT, PeriodInclusion.INCLUDE_CURRENT));
        assertThat(key).isEqualTo("AVG(PAT,2q)");
    }

    @Test
    void periodicKeysAppendTheirKeyword() {
        assertThat(FormulaKeys.key(agg(FunctionKind.AVG, SamplingMode.PERIODIC, PeriodInclusion.DEFAULT)))
                .isEqualTo("AVG(PAT,2q,PERIODIC)");
        assertThat(FormulaKeys.key(agg(FunctionKind.SUM, SamplingMode.PERIODIC, PeriodInclusion.INCLUDE_CURRENT)))
                .isEqualTo("SUM(PAT,2q,PERIODIC_CURRENT)");
        assertThat(FormulaKeys.key(agg(FunctionKind.MAX, SamplingMode.PERIODIC, PeriodInclusion.COMPLETED_ONLY)))
                .isEqualTo("MAX(PAT,2q,PERIODIC_COMPLETED)");
    }

    @Test
    void measureRefKeyIsItsName() {
        assertThat(FormulaKeys.key(new Node.MeasureRef("DSCR", 0, 4))).isEqualTo("DSCR");
    }
}
