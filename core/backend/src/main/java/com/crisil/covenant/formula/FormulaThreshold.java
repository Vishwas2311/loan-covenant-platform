package com.crisil.covenant.formula;

/**
 * An editable constant in a covenant condition — a literal that is one operand of a comparison
 * (the other operand being a measure/expression). Per-CP threshold tuning edits these without
 * touching the formula's structure.
 *
 * @param startOffset  start of the literal in the source (inclusive)
 * @param endOffset    end of the literal in the source (exclusive)
 * @param valueType    NUMBER / STRING / BOOLEAN / DATE
 * @param displayValue editable value without DSL syntax (number {@code 1.5}, string {@code PwC},
 *                     boolean {@code TRUE})
 * @param label        the measure/expression on the other side, e.g. {@code DSCR}, {@code AVG(PAT, '2y')}
 * @param operator     the comparison operator symbol, e.g. {@code >}, {@code >=}, {@code ==}
 */
public record FormulaThreshold(
        int startOffset,
        int endOffset,
        FormulaType valueType,
        String displayValue,
        String label,
        String operator) {
}
