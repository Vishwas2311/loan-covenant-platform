package com.crisil.covenant.formula.web;

import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.formula.FormulaValidationResult;
import com.crisil.covenant.service.MeasureCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Measure-formula authoring support: live validation + sample evaluation while typing, and the
 * grammar metadata that drives the builder palette.
 */
@RestController
@RequestMapping("/api/v1/formulas")
@Tag(name = "Formulas", description = "Validate and sample-evaluate measure formulas")
public class FormulaController {

    private final FormulaEngine engine;
    private final MeasureCatalogService catalogService;

    public FormulaController(FormulaEngine engine, MeasureCatalogService catalogService) {
        this.engine = engine;
        this.catalogService = catalogService;
    }

    @PostMapping("/validate")
    @Operation(summary = "Validate and sample-evaluate a measure formula",
            description = "Parses the expression against the active measure catalog, reporting syntax "
                    + "and semantic errors and (when valid) a sample evaluation. Set requireBoolean to "
                    + "enforce a boolean-typed condition.")
    public ValidateFormulaResponse validate(@Valid @RequestBody ValidateFormulaRequest request) {
        FormulaValidationResult result = engine.validate(
                request.expression(), catalogService.activeCatalog(), request.requireBooleanOrDefault());
        return ValidateFormulaResponse.from(result);
    }

    @GetMapping("/metadata")
    @Operation(summary = "Aggregation functions and time-window units for the builder palette",
            description = "Returns the grammar metadata (aggregation functions, time-window units) "
                    + "the formula builder UI renders as its palette.")
    public FormulaMetadataResponse metadata() {
        return FormulaMetadataResponse.build();
    }

    @PostMapping("/thresholds")
    @Operation(summary = "Extract the editable thresholds (comparison constants) from a condition",
            description = "Returns the comparison constants in the expression as editable threshold "
                    + "slots (value, operator, tracked-value expression) for the threshold editor.")
    public ThresholdsResponse thresholds(@Valid @RequestBody ThresholdsRequest request) {
        return new ThresholdsResponse(engine.thresholds(request.expression()));
    }

    @PostMapping("/apply-thresholds")
    @Operation(summary = "Rebuild a condition with edited thresholds and validate the result",
            description = "Splices the edited threshold values back into the original expression and "
                    + "revalidates it, returning the rebuilt condition and its validation result.")
    public ApplyThresholdsResponse applyThresholds(@Valid @RequestBody ApplyThresholdsRequest request) {
        String rebuilt = engine.applyThresholds(request.expression(), request.safeEdits());
        FormulaValidationResult result =
                engine.validate(rebuilt, catalogService.activeCatalog(), true);
        return new ApplyThresholdsResponse(rebuilt, ValidateFormulaResponse.from(result));
    }
}
