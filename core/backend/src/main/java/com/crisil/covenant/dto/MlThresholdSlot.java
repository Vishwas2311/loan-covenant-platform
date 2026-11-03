package com.crisil.covenant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One threshold extracted from a covenant's expression, sent to the ML service so it can reason over
 * the individual tunable constants without re-parsing the DSL. Mirrors
 * {@link com.crisil.covenant.formula.FormulaThreshold}: {@code label} is the measure/expression on the
 * other side of the comparison (e.g. {@code DSCR}), {@code operator} the comparison symbol
 * (e.g. {@code >=}), {@code value} the editable literal (e.g. {@code 1.5}), and {@code valueType} one
 * of NUMBER / STRING / BOOLEAN / DATE.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlThresholdSlot(
        String label,
        String operator,
        String value,
        String valueType) {
}
