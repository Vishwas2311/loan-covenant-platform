package com.crisil.covenant.service;

import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.formula.FormulaError;
import com.crisil.covenant.formula.FormulaValidationResult;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The single place an {@code AUTO} covenant's condition is validated and its measures extracted.
 *
 * <p>Shared by the covenant library screen ({@link CovenantMasterService}, US-CM-03) and the CP-scoped
 * custom covenant flow ({@link RecommendationService}, US-CM-24) so both produce identical errors and
 * cannot drift — which is the point of US-CM-24: a custom AUTO covenant must behave exactly like a
 * library AUTO covenant.
 */
@Component
public class CovenantConditionValidator {

    private final FormulaEngine formulaEngine;
    private final MeasureCatalogService measureCatalog;

    public CovenantConditionValidator(FormulaEngine formulaEngine, MeasureCatalogService measureCatalog) {
        this.formulaEngine = formulaEngine;
        this.measureCatalog = measureCatalog;
    }

    /**
     * Reject the expression unless it is a valid boolean measure formula.
     *
     * @throws ApiException {@code 400 INVALID_RULE_EXPRESSION} with every validation message joined.
     */
    public void validateOrThrow(String conditionExpression) {
        FormulaValidationResult result =
                formulaEngine.validate(conditionExpression, measureCatalog.activeCatalog(), true);
        if (!result.valid()) {
            String message = result.errors().stream()
                    .map(FormulaError::message)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("Invalid formula");
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RULE_EXPRESSION", message);
        }
    }

    /** The measure codes the expression references, in compilation order. */
    public List<String> referencedMeasures(String conditionExpression) {
        return formulaEngine.compile(conditionExpression, measureCatalog.activeCatalog())
                .referencedMeasures();
    }
}
