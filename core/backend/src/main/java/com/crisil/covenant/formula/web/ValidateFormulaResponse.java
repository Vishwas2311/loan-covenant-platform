package com.crisil.covenant.formula.web;

import com.crisil.covenant.formula.FormulaError;
import com.crisil.covenant.formula.FormulaValidationResult;
import java.util.List;

/**
 * Validation outcome for the formula builder. {@code errors} carry character offsets so the editor
 * can underline problems inline; {@code sampleResolution}/{@code sampleResult} show the dry-run.
 */
public record ValidateFormulaResponse(
        boolean valid,
        List<FormulaErrorDto> errors,
        String sampleResolution,
        String sampleResult) {

    public record FormulaErrorDto(String code, String message, int startOffset, int endOffset) {
        static FormulaErrorDto from(FormulaError e) {
            return new FormulaErrorDto(e.code().name(), e.message(), e.startOffset(), e.endOffset());
        }
    }

    public static ValidateFormulaResponse from(FormulaValidationResult result) {
        List<FormulaErrorDto> errors = result.errors().stream().map(FormulaErrorDto::from).toList();
        return new ValidateFormulaResponse(
                result.valid(), errors, result.sampleResolution(), result.sampleResult());
    }
}
