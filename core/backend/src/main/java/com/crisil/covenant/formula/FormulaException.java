package com.crisil.covenant.formula;

/**
 * Thrown when a formula that was expected to be valid cannot be compiled or evaluated
 * (e.g. a saved covenant whose measures changed, a division by zero at run time, or an
 * attempt to evaluate a formula that never validated). Authoring-time validation never lets
 * this escape — it returns a {@link FormulaValidationResult} instead.
 */
public class FormulaException extends RuntimeException {

    public FormulaException(String message) {
        super(message);
    }
}
