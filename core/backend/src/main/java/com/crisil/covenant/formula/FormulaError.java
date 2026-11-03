package com.crisil.covenant.formula;

/**
 * A single validation problem with a human-readable message and the 0-based character range
 * {@code [startOffset, endOffset)} in the source formula, so the editor can underline it inline.
 */
public record FormulaError(ErrorCode code, String message, int startOffset, int endOffset) {
}
