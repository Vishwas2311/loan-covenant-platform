package com.crisil.covenant.util;

import com.crisil.covenant.formula.FormulaThreshold;
import com.crisil.covenant.formula.FormulaType;
import java.math.BigDecimal;
import java.util.List;

/**
 * Computes covenant headroom (US-MON-16 AC4): the signed distance from the threshold in the
 * compliant direction, for a <strong>single numeric comparison</strong> only. Positive = cushion,
 * negative = breach size. Reuses {@link com.crisil.covenant.formula.FormulaEngine#thresholds(String)}
 * output — no new parsing.
 *
 * <p>Returns {@code null} (grid shows "—") for anything that is not a single numeric comparison:
 * compound/{@code AND}/{@code OR} conditions (≠1 threshold), equality ({@code ==}/{@code !=}),
 * non-numeric literals, percent literals (ambiguous scale versus the stored value), or values that
 * do not parse as plain decimals.
 */
public final class HeadroomCalculator {

    private HeadroomCalculator() {}

    public static BigDecimal compute(String expression, List<FormulaThreshold> thresholds,
                                     String computedValue) {
        if (expression == null || thresholds == null || thresholds.size() != 1) {
            return null;
        }
        FormulaThreshold t = thresholds.get(0);
        if (t.valueType() != FormulaType.NUMBER || !isComparison(t.operator())) {
            return null; // non-numeric literal, or ==/!= (no signed headroom)
        }
        BigDecimal threshold = parsePlainNumber(t.displayValue());
        BigDecimal actual = parsePlainNumber(computedValue);
        if (threshold == null || actual == null) {
            return null;
        }
        return isUpperBound(t.operator(), literalOnLeft(expression, t))
                ? threshold.subtract(actual)  // upper bound ⇒ cushion below the cap
                : actual.subtract(threshold); // lower bound ⇒ cushion above the floor
    }

    /**
     * C3: whether a compliant covenant is close to its limit: headroom is non-negative but below
     * {@code percent}% of the limit's size. {@code false} when headroom cannot be computed, the limit
     * is zero, or {@code percent} is 0 (warning switched off).
     */
    public static boolean atRisk(BigDecimal headroom, List<FormulaThreshold> thresholds, int percent) {
        if (headroom == null || percent <= 0 || headroom.signum() < 0 || thresholds == null
                || thresholds.size() != 1) {
            return false;
        }
        BigDecimal limit = parsePlainNumber(thresholds.get(0).displayValue());
        if (limit == null || limit.signum() == 0) {
            return false;
        }
        BigDecimal margin = limit.abs().multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100));
        return headroom.compareTo(margin) < 0;
    }

    /** Whether the literal operand sits to the left of the measure/expression operand. */
    private static boolean literalOnLeft(String expression, FormulaThreshold t) {
        int labelIdx = t.label() == null ? -1 : expression.indexOf(t.label());
        return labelIdx < 0 || t.startOffset() < labelIdx;
    }

    /** A single numeric bound comparison ({@code < <= > >=}); {@code == !=} have no signed headroom. */
    private static boolean isComparison(String operator) {
        return switch (operator) {
            case "<", "<=", ">", ">=" -> true;
            default -> false;
        };
    }

    /**
     * Resolve the compliant direction for a bound comparison: {@code true} = upper bound (compliant
     * when actual ≤ threshold ⇒ headroom = threshold − actual); {@code false} = lower bound
     * (compliant when actual ≥ threshold ⇒ headroom = actual − threshold). A {@code <}/{@code <=}
     * with the literal on the left (e.g. {@code k < measure}) is a lower bound, and vice-versa —
     * hence the XOR of "operator is less-than" with "literal is on the left".
     */
    private static boolean isUpperBound(String operator, boolean literalOnLeft) {
        boolean lessThan = "<".equals(operator) || "<=".equals(operator);
        return lessThan != literalOnLeft;
    }

    /** Parse a plain decimal; reject percent (ambiguous scale) and non-numeric ⇒ {@code null}. */
    private static BigDecimal parsePlainNumber(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty() || t.endsWith("%")) {
            return null;
        }
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
