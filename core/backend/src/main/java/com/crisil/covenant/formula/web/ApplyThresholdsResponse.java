package com.crisil.covenant.formula.web;

/** The rebuilt condition plus its validation/sample-evaluation result. */
public record ApplyThresholdsResponse(String expression, ValidateFormulaResponse validation) {
}
