package com.crisil.covenant.formula.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to validate (and sample-evaluate) a measure formula without persisting it.
 * {@code requireBoolean} defaults to {@code true} — a covenant condition must be a true/false test;
 * pass {@code false} to validate a tracked-value (scalar) formula.
 */
public record ValidateFormulaRequest(
        @NotBlank @Size(max = 2000) String expression,
        Boolean requireBoolean) {

    public boolean requireBooleanOrDefault() {
        return requireBoolean == null || requireBoolean;
    }
}
