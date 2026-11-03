package com.crisil.covenant.formula;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FunctionKindTest {

    @Test
    void fromNameIsCaseInsensitive() {
        assertThat(FunctionKind.fromName("AVG")).contains(FunctionKind.AVG);
        assertThat(FunctionKind.fromName("avg")).contains(FunctionKind.AVG);
        assertThat(FunctionKind.fromName("Latest")).contains(FunctionKind.LATEST);
        assertThat(FunctionKind.fromName("current")).contains(FunctionKind.CURRENT);
        assertThat(FunctionKind.fromName("COUNT")).contains(FunctionKind.COUNT);
    }

    @Test
    void fromNameEmptyForUnknownOrNull() {
        assertThat(FunctionKind.fromName("AVERAGE")).isEmpty();
        assertThat(FunctionKind.fromName(null)).isEmpty();
    }

    @Test
    void numericFunctionsRequireNumericInner() {
        assertThat(FunctionKind.AVG.requiresNumericInner()).isTrue();
        assertThat(FunctionKind.SUM.requiresNumericInner()).isTrue();
        assertThat(FunctionKind.MIN.requiresNumericInner()).isTrue();
        assertThat(FunctionKind.MAX.requiresNumericInner()).isTrue();
        assertThat(FunctionKind.COUNT.requiresNumericInner()).isFalse();
        assertThat(FunctionKind.LATEST.requiresNumericInner()).isFalse();
        assertThat(FunctionKind.CURRENT.requiresNumericInner()).isFalse();
    }

    @Test
    void resultTypeOfNumericAggregationsIsNumber() {
        assertThat(FunctionKind.AVG.resultType(FormulaType.NUMBER)).isEqualTo(FormulaType.NUMBER);
        assertThat(FunctionKind.SUM.resultType(FormulaType.NUMBER)).isEqualTo(FormulaType.NUMBER);
        assertThat(FunctionKind.MIN.resultType(FormulaType.NUMBER)).isEqualTo(FormulaType.NUMBER);
        assertThat(FunctionKind.MAX.resultType(FormulaType.NUMBER)).isEqualTo(FormulaType.NUMBER);
    }

    @Test
    void countAlwaysReturnsNumber() {
        assertThat(FunctionKind.COUNT.resultType(FormulaType.STRING)).isEqualTo(FormulaType.NUMBER);
        assertThat(FunctionKind.COUNT.resultType(FormulaType.DATE)).isEqualTo(FormulaType.NUMBER);
    }

    @Test
    void latestAndCurrentPreserveInnerType() {
        assertThat(FunctionKind.LATEST.resultType(FormulaType.DATE)).isEqualTo(FormulaType.DATE);
        assertThat(FunctionKind.CURRENT.resultType(FormulaType.STRING)).isEqualTo(FormulaType.STRING);
        assertThat(FunctionKind.LATEST.resultType(FormulaType.NUMBER)).isEqualTo(FormulaType.NUMBER);
    }
}
