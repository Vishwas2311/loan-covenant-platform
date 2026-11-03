package com.crisil.covenant.formula;

/** Classifies a {@link FormulaError} so the UI can style/group it. */
public enum ErrorCode {
    EMPTY_FORMULA,
    FORMULA_TOO_LONG,
    TOO_DEEP,
    TOO_MANY_TERMS,
    LEX_ERROR,
    PARSE_ERROR,
    UNKNOWN_MEASURE,
    UNKNOWN_FUNCTION,
    BAD_ARITY,
    ARG_TYPE,
    INVALID_WINDOW,
    INVALID_SAMPLING,
    TYPE_MISMATCH_COMPARE,
    TYPE_MISMATCH_ARITH,
    TYPE_MISMATCH_LOGICAL,
    CHAINED_COMPARISON,
    NON_BOOLEAN_CONDITION,
    EVAL_ERROR
}
