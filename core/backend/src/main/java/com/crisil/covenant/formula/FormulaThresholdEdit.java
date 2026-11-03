package com.crisil.covenant.formula;

/**
 * A requested change to one threshold: replace the literal at {@code [startOffset, endOffset)}
 * with {@code value}, formatted according to {@code valueType}.
 */
public record FormulaThresholdEdit(
        int startOffset,
        int endOffset,
        FormulaType valueType,
        String value) {
}
