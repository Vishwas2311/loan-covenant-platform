package com.crisil.covenant.formula;

/**
 * Internal carrier for a single lex/parse-time error with its source offsets and code.
 * {@link FormulaEngine} converts it into a {@link FormulaError}; it never reaches callers.
 */
final class FormulaSyntaxException extends RuntimeException {

    private final ErrorCode code;
    private final int startOffset;
    private final int endOffset;

    FormulaSyntaxException(ErrorCode code, String message, int startOffset, int endOffset) {
        super(message);
        this.code = code;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
    }

    FormulaError toError() {
        return new FormulaError(code, getMessage(), startOffset, endOffset);
    }
}
