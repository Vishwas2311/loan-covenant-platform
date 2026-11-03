package com.crisil.covenant.formula;

import java.util.List;

/**
 * The outcome of validating a formula while typing. When {@code valid}, {@code sampleResolution}
 * is the formula with measure values substituted (e.g. {@code 2.0 > 1.2}) and {@code sampleResult}
 * is the dry-run result (e.g. {@code true}). When invalid, {@code errors} carry messages + offsets.
 */
public record FormulaValidationResult(
        boolean valid,
        List<FormulaError> errors,
        String sampleResolution,
        String sampleResult) {

    static FormulaValidationResult ok(String sampleResolution, String sampleResult) {
        return new FormulaValidationResult(true, List.of(), sampleResolution, sampleResult);
    }

    static FormulaValidationResult fail(List<FormulaError> errors) {
        return new FormulaValidationResult(false, List.copyOf(errors), null, null);
    }

    static FormulaValidationResult fail(FormulaError error) {
        return new FormulaValidationResult(false, List.of(error), null, null);
    }
}
