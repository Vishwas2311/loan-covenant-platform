package com.crisil.covenant.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.formula.FormulaType;
import org.junit.jupiter.api.Test;

class AssessmentThresholdTest {

    @Test
    void noArgConstructorPresent() {
        AssessmentThreshold t = new AssessmentThreshold();
        assertThat(t.getId()).isNull();
        assertThat(t.getAssessment()).isNull();
    }

    @Test
    void allArgsConstructorPopulatesFields() {
        AssessmentThreshold t = new AssessmentThreshold(
                2, "DSCR", ">=", "1.25", "1.85", FormulaType.NUMBER);

        assertThat(t.getOrdinal()).isEqualTo(2);
        assertThat(t.getMeasure()).isEqualTo("DSCR");
        assertThat(t.getOperator()).isEqualTo(">=");
        assertThat(t.getThresholdValue()).isEqualTo("1.25");
        assertThat(t.getActualValue()).isEqualTo("1.85");
        assertThat(t.getValueType()).isEqualTo(FormulaType.NUMBER);
    }

    @Test
    void setAssessmentLinksParent() {
        Assessment parent = new Assessment();
        AssessmentThreshold t = new AssessmentThreshold(
                0, "ICR", ">", "2", null, FormulaType.NUMBER);

        t.setAssessment(parent);

        assertThat(t.getAssessment()).isSameAs(parent);
        assertThat(t.getActualValue()).isNull();
    }
}
