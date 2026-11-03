package com.crisil.covenant.formula;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests the compile/evaluate/render/extract API used by the assessment and validation layers. */
class FormulaEngineApiTest {

    private final FormulaEngine engine = new FormulaEngine();
    private final MeasureCatalog catalog = FormulaTestSupport.catalog();

    @Test
    void compileExposesMeasuresAndTerms() {
        CompiledFormula f = engine.compile("AVG(PAT, '2y') > 1 AND DSCR > 0", catalog);
        assertThat(f.type()).isEqualTo(FormulaType.BOOLEAN);
        assertThat(f.referencedMeasures()).containsExactlyInAnyOrder("PAT", "DSCR");
        assertThat(f.terms()).hasSize(2);
        FormulaTerm aggregation = f.terms().stream().filter(FormulaTerm::isAggregation).findFirst().orElseThrow();
        assertThat(aggregation.measure()).isEqualTo("PAT");
        assertThat(aggregation.aggregation()).isEqualTo(FunctionKind.AVG);
        assertThat(aggregation.window().canonical()).isEqualTo("2y");
        assertThat(aggregation.valueKey()).isEqualTo("AVG(PAT,2y)");
    }

    @Test
    void compileDeduplicatesRepeatedTerms() {
        CompiledFormula f = engine.compile("DSCR > 1 OR DSCR < 0", catalog);
        assertThat(f.terms()).hasSize(1);
        assertThat(f.referencedMeasures()).containsExactly("DSCR");
    }

    @Test
    void compileThrowsOnSyntaxError() {
        assertThatThrownBy(() -> engine.compile("DSCR >", catalog))
                .isInstanceOf(FormulaException.class);
    }

    @Test
    void compileThrowsOnUnknownMeasure() {
        assertThatThrownBy(() -> engine.compile("Nope > 1", catalog))
                .isInstanceOf(FormulaException.class)
                .hasMessageContaining("Unknown measure");
    }

    @Test
    void evaluateReturnsBooleanResult() {
        CompiledFormula f = engine.compile("DSCR > 1.2", catalog);
        assertThat(engine.evaluate(f, Map.of("DSCR", 2.0))).isTrue();
        assertThat(engine.evaluate(f, Map.of("DSCR", 0.5))).isFalse();
    }

    @Test
    void evaluateAggregationByValueKey() {
        CompiledFormula f = engine.compile("AVG(PAT, '2y') > 1", catalog);
        assertThat(engine.evaluate(f, Map.of("AVG(PAT,2y)", 1.5))).isTrue();
        assertThat(engine.evaluate(f, Map.of("AVG(PAT,2y)", 0.5))).isFalse();
    }

    @Test
    void evaluateRawReturnsScalar() {
        CompiledFormula f = engine.compile("DSCR + EBITDA", catalog);
        assertThat(engine.evaluateRaw(f, Map.of("DSCR", 2.0, "EBITDA", 3.0))).isEqualTo(5.0);
    }

    @Test
    void evaluateRawWithNullValuesMapDoesNotThrowForConstant() {
        CompiledFormula f = engine.compile("1 + 1", catalog);
        assertThat(engine.evaluateRaw(f, null)).isEqualTo(2.0);
    }

    @Test
    void evaluateRejectsNonBooleanResult() {
        CompiledFormula f = engine.compile("DSCR + 1", catalog);
        assertThatThrownBy(() -> engine.evaluate(f, Map.of("DSCR", 1.0)))
                .isInstanceOf(FormulaException.class)
                .hasMessageContaining("true/false");
    }

    @Test
    void evaluateThrowsWhenValueMissing() {
        CompiledFormula f = engine.compile("DSCR > 1", catalog);
        assertThatThrownBy(() -> engine.evaluate(f, Map.of()))
                .isInstanceOf(FormulaException.class)
                .hasMessageContaining("No value available");
    }

    @Test
    void evaluateThrowsWhenValueHasWrongType() {
        CompiledFormula f = engine.compile("DSCR > 1", catalog);
        assertThatThrownBy(() -> engine.evaluate(f, Map.of("DSCR", "oops")))
                .isInstanceOf(FormulaException.class)
                .hasMessageContaining("Cannot compare");
    }

    @Test
    void arithmeticOnWrongTypeThrows() {
        CompiledFormula f = engine.compile("DSCR + 1 > 0", catalog);
        assertThatThrownBy(() -> engine.evaluate(f, Map.of("DSCR", "oops")))
                .isInstanceOf(FormulaException.class)
                .hasMessageContaining("Expected a number");
    }

    @Test
    void logicalOnWrongTypeThrows() {
        CompiledFormula f = engine.compile("insuranceActive AND insuranceActive", catalog);
        assertThatThrownBy(() -> engine.evaluate(f, Map.of("insuranceActive", 5.0)))
                .isInstanceOf(FormulaException.class)
                .hasMessageContaining("Expected true/false");
    }

    @Test
    void divisionByZeroThrowsAtRuntime() {
        CompiledFormula f = engine.compile("DSCR / 0 > 1", catalog);
        assertThatThrownBy(() -> engine.evaluate(f, Map.of("DSCR", 2.0)))
                .isInstanceOf(FormulaException.class)
                .hasMessageContaining("Division by zero");
    }

    @Test
    void coversEveryOperator() {
        assertThat(engine.evaluate(engine.compile("DSCR <= 2", catalog), Map.of("DSCR", 2.0))).isTrue();
        assertThat(engine.evaluate(engine.compile("DSCR >= 2", catalog), Map.of("DSCR", 2.0))).isTrue();
        assertThat(engine.evaluate(engine.compile("DSCR < 3", catalog), Map.of("DSCR", 2.0))).isTrue();
        assertThat(engine.evaluate(engine.compile("DSCR != 1", catalog), Map.of("DSCR", 2.0))).isTrue();
        assertThat(engine.evaluate(engine.compile("auditor != 'x'", catalog), Map.of("auditor", "y"))).isTrue();
        assertThat(engine.evaluate(engine.compile("DSCR - 1 > 0", catalog), Map.of("DSCR", 2.0))).isTrue();
        assertThat(engine.evaluate(engine.compile("DSCR / 2 > 0", catalog), Map.of("DSCR", 2.0))).isTrue();
        // OR with a false left operand must evaluate the right operand
        assertThat(engine.evaluate(engine.compile("DSCR > 5 OR EBITDA > 1", catalog),
                Map.of("DSCR", 2.0, "EBITDA", 2.0))).isTrue();
        // AND with a true left operand must evaluate the right operand
        assertThat(engine.evaluate(engine.compile("DSCR > 1 AND EBITDA > 5", catalog),
                Map.of("DSCR", 2.0, "EBITDA", 2.0))).isFalse();
        assertThat(engine.evaluate(engine.compile("LATEST(lastAuditDate, '1y') == DATE('2026-01-01')", catalog),
                Map.of("LATEST(lastAuditDate,1y)", LocalDate.of(2026, 1, 1)))).isTrue();
    }

    @Test
    void renderResolvedSubstitutesValues() {
        CompiledFormula f = engine.compile("DSCR > 1.2", catalog);
        assertThat(engine.renderResolved(f, Map.of("DSCR", 2.1))).isEqualTo("2.1 > 1.2");
    }

    @Test
    void renderResolvedFormatsStringsDatesAndBooleans() {
        CompiledFormula f = engine.compile(
                "LATEST(auditor, '6m') == 'KPMG' AND LATEST(lastAuditDate, '1y') > DATE('2026-01-01')",
                catalog);
        Map<String, Object> values = Map.of(
                "LATEST(auditor,6m)", "KPMG",
                "LATEST(lastAuditDate,1y)", LocalDate.of(2026, 6, 4));
        String rendered = engine.renderResolved(f, values);
        assertThat(rendered).contains("'KPMG' == 'KPMG'");
        assertThat(rendered).contains("2026-06-04 > DATE('2026-01-01')");
    }

    @Test
    void renderResolvedHandlesMissingValueAsNull() {
        CompiledFormula f = engine.compile("DSCR > 1", catalog);
        assertThat(engine.renderResolved(f, Map.of())).isEqualTo("null > 1");
        assertThat(engine.renderResolved(f, null)).isEqualTo("null > 1");
    }

    @Test
    void trackedValueExpressionFromComparison() {
        assertThat(engine.trackedValueExpression(engine.compile("DSCR > 1.2", catalog)))
                .contains("DSCR");
        assertThat(engine.trackedValueExpression(engine.compile("AVG(PAT, '2y') > 1", catalog)))
                .contains("AVG(PAT, '2y')");
    }

    @Test
    void trackedValueExpressionFromConjunctionTakesLeftmost() {
        assertThat(engine.trackedValueExpression(engine.compile("PAT > 1 AND EBITDA > 2", catalog)))
                .contains("PAT");
    }

    @Test
    void trackedValueExpressionFromBareMeasure() {
        assertThat(engine.trackedValueExpression(engine.compile("DSCR", catalog))).contains("DSCR");
        assertThat(engine.trackedValueExpression(engine.compile("-DSCR + 1", catalog))).contains("DSCR");
    }

    @Test
    void trackedValueExpressionEmptyWhenNoValueNode() {
        assertThat(engine.trackedValueExpression(engine.compile("TRUE", catalog))).isEmpty();
    }
}
