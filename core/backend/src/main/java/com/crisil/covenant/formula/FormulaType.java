package com.crisil.covenant.formula;

/**
 * The logical type a formula (sub)expression evaluates to. Mirrors
 * {@link com.crisil.covenant.enums.DataColumnType} but lives in the formula package so the
 * language layer has no dependency direction back into persistence.
 */
public enum FormulaType {
    NUMBER,
    STRING,
    DATE,
    BOOLEAN
}
