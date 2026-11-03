package com.crisil.covenant.formula;

/**
 * The minimal metadata the language layer needs about a measure: its formula-facing {@code code},
 * its {@link FormulaType}, and an optional explicit sample value for dry-run evaluation. When
 * {@code sampleValue} is {@code null} a type-appropriate default is used.
 */
public record MeasureMeta(String code, FormulaType type, Object sampleValue) {

    public static MeasureMeta of(String code, FormulaType type) {
        return new MeasureMeta(code, type, null);
    }
}
