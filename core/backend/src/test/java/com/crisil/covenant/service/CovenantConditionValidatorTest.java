package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.formula.CompiledFormula;
import com.crisil.covenant.formula.ErrorCode;
import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.formula.FormulaError;
import com.crisil.covenant.formula.FormulaValidationResult;
import com.crisil.covenant.formula.MeasureCatalog;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * The condition contract both authoring paths share: the covenant library screen (US-CM-03) and the
 * CP-scoped custom covenant flow (US-CM-24). Whatever this class does, both screens do.
 */
@ExtendWith(MockitoExtension.class)
class CovenantConditionValidatorTest {

    @Mock private FormulaEngine formulaEngine;
    @Mock private MeasureCatalogService measureCatalog;

    @InjectMocks private CovenantConditionValidator validator;

    private MeasureCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = mock(MeasureCatalog.class);
    }

    @Test
    void validExpressionPasses() {
        when(measureCatalog.activeCatalog()).thenReturn(catalog);
        when(formulaEngine.validate("DSCR > 1.2", catalog, true))
                .thenReturn(new FormulaValidationResult(true, List.of(), "2.0 > 1.2", "true"));

        assertThatCode(() -> validator.validateOrThrow("DSCR > 1.2")).doesNotThrowAnyException();
    }

    @Test
    void invalidExpressionThrowsBadRequestWithTheValidationMessage() {
        when(measureCatalog.activeCatalog()).thenReturn(catalog);
        when(formulaEngine.validate(anyString(), any(), anyBoolean())).thenReturn(
                new FormulaValidationResult(false,
                        List.of(new FormulaError(ErrorCode.UNKNOWN_MEASURE, "Unknown measure 'foo'", 0, 3)),
                        null, null));

        assertThatThrownBy(() -> validator.validateOrThrow("foo > 1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unknown measure 'foo'")
                .extracting(e -> ((ApiException) e).getStatus(), e -> ((ApiException) e).getCode())
                .containsExactly(HttpStatus.BAD_REQUEST, "INVALID_RULE_EXPRESSION");
    }

    @Test
    void everyErrorMessageIsReported() {
        when(measureCatalog.activeCatalog()).thenReturn(catalog);
        when(formulaEngine.validate(anyString(), any(), anyBoolean())).thenReturn(
                new FormulaValidationResult(false, List.of(
                        new FormulaError(ErrorCode.UNKNOWN_MEASURE, "bad one", 0, 1),
                        new FormulaError(ErrorCode.TYPE_MISMATCH_COMPARE, "bad two", 2, 3)),
                        null, null));

        assertThatThrownBy(() -> validator.validateOrThrow("nonsense"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("bad one")
                .hasMessageContaining("bad two");
    }

    /** Defensive: an invalid result with no error detail still fails, with a generic message. */
    @Test
    void invalidWithNoErrorsFallsBackToAGenericMessage() {
        when(measureCatalog.activeCatalog()).thenReturn(catalog);
        when(formulaEngine.validate(anyString(), any(), anyBoolean()))
                .thenReturn(new FormulaValidationResult(false, List.of(), null, null));

        assertThatThrownBy(() -> validator.validateOrThrow("?"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid formula");
    }

    @Test
    void referencedMeasuresComesFromTheCompiledFormula() {
        when(measureCatalog.activeCatalog()).thenReturn(catalog);
        CompiledFormula compiled = mock(CompiledFormula.class);
        when(compiled.referencedMeasures()).thenReturn(List.of("DSCR", "EBITDA"));
        when(formulaEngine.compile("DSCR > 1.2 AND EBITDA > 0", catalog)).thenReturn(compiled);

        assertThat(validator.referencedMeasures("DSCR > 1.2 AND EBITDA > 0"))
                .containsExactly("DSCR", "EBITDA");
    }
}
