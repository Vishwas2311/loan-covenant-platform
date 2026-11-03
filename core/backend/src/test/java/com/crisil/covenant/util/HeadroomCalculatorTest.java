package com.crisil.covenant.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.formula.FormulaThreshold;
import com.crisil.covenant.formula.FormulaType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * US-MON-16 AC4 headroom: signed distance from threshold in the compliant direction, for a single
 * numeric comparison only. Positive = cushion, negative = breach size; "—" (null) otherwise.
 */
class HeadroomCalculatorTest {

    private static FormulaThreshold num(String expression, String value, String label, String op) {
        int start = expression.indexOf(value);
        return new FormulaThreshold(start, start + value.length(), FormulaType.NUMBER, value, label, op);
    }

    @Test
    void upperBoundMeasureLeftGivesThresholdMinusActual() {
        String expr = "DSCR < 1.5";
        BigDecimal h = HeadroomCalculator.compute(expr, List.of(num(expr, "1.5", "DSCR", "<")), "1.2");
        assertThat(h).isEqualByComparingTo("0.3"); // cushion below the cap
    }

    @Test
    void upperBoundBreachIsNegative() {
        String expr = "DSCR <= 1.5";
        BigDecimal h = HeadroomCalculator.compute(expr, List.of(num(expr, "1.5", "DSCR", "<=")), "1.8");
        assertThat(h).isEqualByComparingTo("-0.3");
    }

    @Test
    void lowerBoundMeasureLeftGivesActualMinusThreshold() {
        String expr = "DSCR > 1.2";
        BigDecimal h = HeadroomCalculator.compute(expr, List.of(num(expr, "1.2", "DSCR", ">")), "1.5");
        assertThat(h).isEqualByComparingTo("0.3"); // cushion above the floor
    }

    @Test
    void lowerBoundBreachIsNegative() {
        String expr = "DSCR >= 1.2";
        BigDecimal h = HeadroomCalculator.compute(expr, List.of(num(expr, "1.2", "DSCR", ">=")), "1.0");
        assertThat(h).isEqualByComparingTo("-0.2");
    }

    @Test
    void literalOnLeftFlipsDirection() {
        // "1.5 > DSCR" is an upper bound on DSCR ⇒ threshold − actual.
        String expr = "1.5 > DSCR";
        BigDecimal h = HeadroomCalculator.compute(expr, List.of(num(expr, "1.5", "DSCR", ">")), "1.2");
        assertThat(h).isEqualByComparingTo("0.3");
    }

    @Test
    void equalityHasNoSignedHeadroom() {
        String expr = "RATING == 1";
        assertThat(HeadroomCalculator.compute(expr, List.of(num(expr, "1", "RATING", "==")), "1")).isNull();
    }

    @Test
    void nonNumericThresholdIsNull() {
        String expr = "AUDITOR == 'PwC'";
        FormulaThreshold t = new FormulaThreshold(11, 15, FormulaType.STRING, "PwC", "AUDITOR", "==");
        assertThat(HeadroomCalculator.compute(expr, List.of(t), "PwC")).isNull();
    }

    @Test
    void percentLiteralIsNullAmbiguousScale() {
        String expr = "MARGIN >= 80%";
        assertThat(HeadroomCalculator.compute(expr, List.of(num(expr, "80%", "MARGIN", ">=")), "0.9"))
                .isNull();
    }

    @Test
    void compoundConditionWithTwoThresholdsIsNull() {
        String expr = "DSCR > 1.2 AND DSCR < 3";
        assertThat(HeadroomCalculator.compute(expr,
                List.of(num(expr, "1.2", "DSCR", ">"), num("3", "3", "DSCR", "<")), "1.5")).isNull();
    }

    @Test
    void noThresholdsIsNull() {
        assertThat(HeadroomCalculator.compute("DSCR", List.of(), "1.5")).isNull();
    }

    @Test
    void nullExpressionOrThresholdsIsNull() {
        assertThat(HeadroomCalculator.compute(null, List.of(num("x 1", "1", "x", ">")), "1")).isNull();
        assertThat(HeadroomCalculator.compute("DSCR > 1", null, "1")).isNull();
    }

    @Test
    void unparseableOrMissingActualIsNull() {
        String expr = "DSCR > 1.2";
        assertThat(HeadroomCalculator.compute(expr, List.of(num(expr, "1.2", "DSCR", ">")), null)).isNull();
        assertThat(HeadroomCalculator.compute(expr, List.of(num(expr, "1.2", "DSCR", ">")), "n/a")).isNull();
        assertThat(HeadroomCalculator.compute(expr, List.of(num(expr, "1.2", "DSCR", ">")), "  ")).isNull();
    }

    @Test
    void nullLabelTreatedAsLiteralOnLeft() {
        // label null ⇒ literalOnLeft true; ">" ⇒ upper bound ⇒ threshold − actual.
        FormulaThreshold t = new FormulaThreshold(4, 5, FormulaType.NUMBER, "1", null, ">");
        assertThat(HeadroomCalculator.compute("x > 1", List.of(t), "3")).isEqualByComparingTo("-2");
    }

    @Test
    void labelAbsentFromExpressionTreatedAsLiteralOnLeft() {
        // label not found ⇒ literalOnLeft true; with ">" that is an upper bound ⇒ threshold − actual.
        FormulaThreshold t = new FormulaThreshold(0, 3, FormulaType.NUMBER, "1.5", "MISSING", ">");
        assertThat(HeadroomCalculator.compute("1.5 > x", List.of(t), "1.0")).isEqualByComparingTo("0.5");
    }
}
