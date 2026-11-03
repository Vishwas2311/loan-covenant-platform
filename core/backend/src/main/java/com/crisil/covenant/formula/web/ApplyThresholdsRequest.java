package com.crisil.covenant.formula.web;

import com.crisil.covenant.formula.FormulaThresholdEdit;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request to rebuild a condition by applying threshold edits, then validate the result. */
public record ApplyThresholdsRequest(
        @NotBlank @Size(max = 2000) String expression,
        List<FormulaThresholdEdit> edits) {

    public List<FormulaThresholdEdit> safeEdits() {
        return edits == null ? List.of() : edits;
    }
}
