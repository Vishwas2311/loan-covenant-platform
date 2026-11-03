package com.crisil.covenant.formula;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FormulaThresholdsTest {

    private final FormulaEngine engine = new FormulaEngine();

    private FormulaThreshold only(String expression) {
        List<FormulaThreshold> t = engine.thresholds(expression);
        assertThat(t).hasSize(1);
        return t.get(0);
    }

    @Test
    void extractsNumericThreshold() {
        FormulaThreshold t = only("DSCR > 1.5");
        assertThat(t.valueType()).isEqualTo(FormulaType.NUMBER);
        assertThat(t.displayValue()).isEqualTo("1.5");
        assertThat(t.label()).isEqualTo("DSCR");
        assertThat(t.operator()).isEqualTo(">");
        assertThat("DSCR > 1.5".substring(t.startOffset(), t.endOffset())).isEqualTo("1.5");
    }

    @Test
    void extractsTwoThresholdsFromConjunction() {
        List<FormulaThreshold> t = engine.thresholds("DSCR > 1.5 AND ICR > 2");
        assertThat(t).hasSize(2);
        assertThat(t).extracting(FormulaThreshold::label).containsExactly("DSCR", "ICR");
        assertThat(t).extracting(FormulaThreshold::displayValue).containsExactly("1.5", "2");
    }

    @Test
    void extractsStringThresholdUnquoted() {
        FormulaThreshold t = only("auditor == 'PwC'");
        assertThat(t.valueType()).isEqualTo(FormulaType.STRING);
        assertThat(t.displayValue()).isEqualTo("PwC");
        assertThat(t.label()).isEqualTo("auditor");
    }

    @Test
    void extractsBooleanThreshold() {
        FormulaThreshold t = only("insuranceActive == TRUE");
        assertThat(t.valueType()).isEqualTo(FormulaType.BOOLEAN);
        assertThat(t.displayValue()).isEqualTo("TRUE");
    }

    @Test
    void labelOfAggregationThreshold() {
        FormulaThreshold t = only("AVG(PAT, '2y') > 50");
        assertThat(t.label()).isEqualTo("AVG(PAT, '2y')");
        assertThat(t.displayValue()).isEqualTo("50");
    }

    @Test
    void percentAndNegativeNumbersAreThresholds() {
        assertThat(only("ratio > 50%").displayValue()).isEqualTo("50%");
        assertThat(only("score > -5").displayValue()).isEqualTo("-5");
    }

    @Test
    void measureVsMeasureHasNoThreshold() {
        assertThat(engine.thresholds("DSCR > ICR")).isEmpty();
    }

    @Test
    void constantVsConstantHasNoThreshold() {
        assertThat(engine.thresholds("1 == 1")).isEmpty();
    }

    @Test
    void unparseableExpressionYieldsNoThresholds() {
        assertThat(engine.thresholds("DSCR >")).isEmpty();
        assertThat(engine.thresholds("")).isEmpty();
    }

    @Test
    void rebuildReplacesNumericThreshold() {
        FormulaThreshold t = only("DSCR > 1.5");
        String rebuilt = engine.applyThresholds("DSCR > 1.5",
                List.of(new FormulaThresholdEdit(t.startOffset(), t.endOffset(), FormulaType.NUMBER, "1.8")));
        assertThat(rebuilt).isEqualTo("DSCR > 1.8");
    }

    @Test
    void rebuildQuotesAndEscapesStrings() {
        FormulaThreshold t = only("auditor == 'PwC'");
        String rebuilt = engine.applyThresholds("auditor == 'PwC'",
                List.of(new FormulaThresholdEdit(t.startOffset(), t.endOffset(), FormulaType.STRING, "O'Brien")));
        assertThat(rebuilt).isEqualTo("auditor == 'O''Brien'");
    }

    @Test
    void rebuildNormalisesBooleans() {
        FormulaThreshold t = only("insuranceActive == TRUE");
        String rebuilt = engine.applyThresholds("insuranceActive == TRUE",
                List.of(new FormulaThresholdEdit(t.startOffset(), t.endOffset(), FormulaType.BOOLEAN, "false")));
        assertThat(rebuilt).isEqualTo("insuranceActive == FALSE");
    }

    @Test
    void rebuildAppliesMultipleEditsRightToLeft() {
        List<FormulaThreshold> t = engine.thresholds("DSCR > 1.5 AND ICR > 2");
        String rebuilt = engine.applyThresholds("DSCR > 1.5 AND ICR > 2", List.of(
                new FormulaThresholdEdit(t.get(0).startOffset(), t.get(0).endOffset(), FormulaType.NUMBER, "1.7"),
                new FormulaThresholdEdit(t.get(1).startOffset(), t.get(1).endOffset(), FormulaType.NUMBER, "3")));
        assertThat(rebuilt).isEqualTo("DSCR > 1.7 AND ICR > 3");
    }

    @Test
    void rebuildIgnoresOutOfRangeEdits() {
        String rebuilt = engine.applyThresholds("DSCR > 1.5",
                List.of(new FormulaThresholdEdit(100, 200, FormulaType.NUMBER, "9")));
        assertThat(rebuilt).isEqualTo("DSCR > 1.5");
        assertThat(engine.applyThresholds(null, List.of())).isEmpty();
    }

    @Test
    void rebuildFormatsDateLiteral() {
        // DATE('2026-01-01') threshold rebuilt to a new date.
        FormulaThreshold t = only("LATEST(lastAuditDate, '1y') > DATE('2026-01-01')");
        String rebuilt = engine.applyThresholds("LATEST(lastAuditDate, '1y') > DATE('2026-01-01')",
                List.of(new FormulaThresholdEdit(t.startOffset(), t.endOffset(), FormulaType.DATE, "2027-03-31")));
        assertThat(rebuilt).isEqualTo("LATEST(lastAuditDate, '1y') > DATE('2027-03-31')");
    }
}
