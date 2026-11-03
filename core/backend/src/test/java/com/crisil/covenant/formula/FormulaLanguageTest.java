package com.crisil.covenant.formula;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Exercises the full language pipeline (lex → parse → type-check → sample-evaluate → render)
 * through {@link FormulaEngine#validate}, covering the happy paths and every {@link ErrorCode}.
 */
class FormulaLanguageTest {

    private final FormulaEngine engine = new FormulaEngine();
    private final MeasureCatalog catalog = FormulaTestSupport.catalog();

    private FormulaValidationResult cond(String formula) {
        return engine.validate(formula, catalog, true);
    }

    private FormulaValidationResult value(String formula) {
        return engine.validate(formula, catalog, false);
    }

    private static boolean has(FormulaValidationResult r, ErrorCode code) {
        return r.errors().stream().anyMatch(e -> e.code() == code);
    }

    // ---------------------------------------------------------------- happy paths

    @Test
    void simpleComparisonIsValid() {
        FormulaValidationResult r = cond("DSCR > 1.2");
        assertThat(r.valid()).isTrue();
        assertThat(r.sampleResolution()).isEqualTo("2.0 > 1.2");
        assertThat(r.sampleResult()).isEqualTo("true");
    }

    @Test
    void averageAggregationIsValid() {
        FormulaValidationResult r = cond("AVG(PAT, '2y') > 1");
        assertThat(r.valid()).isTrue();
        assertThat(r.sampleResolution()).isEqualTo("2.0 > 1");
        assertThat(r.sampleResult()).isEqualTo("true");
    }

    @Test
    void latestAggregationIsValid() {
        FormulaValidationResult r = cond("LATEST(EBITDA, '6m') > 2");
        assertThat(r.valid()).isTrue();
        assertThat(r.sampleResult()).isEqualTo("false");
    }

    @Test
    void conjunctionOfComparisonsIsValid() {
        FormulaValidationResult r = cond("PAT > 1 AND EBITDA > 2");
        assertThat(r.valid()).isTrue();
        assertThat(r.sampleResolution()).isEqualTo("(2.0 > 1) AND (2.0 > 2)");
        assertThat(r.sampleResult()).isEqualTo("false");
    }

    @Test
    void parenthesisedConjunctionWithAggregationsIsValid() {
        assertThat(cond("(AVG(DSCR, '2y') >= 1.2) AND (SUM(PAT, '1y') > 100)").valid()).isTrue();
    }

    @Test
    void percentLiteralIsValid() {
        FormulaValidationResult r = cond("ratio > 50%");
        assertThat(r.valid()).isTrue();
        assertThat(r.sampleResolution()).isEqualTo("0.6 > 50%");
        assertThat(r.sampleResult()).isEqualTo("true");
    }

    @Test
    void countOverNonNumericMeasureIsValid() {
        assertThat(cond("COUNT(auditor, '1y') > 0").valid()).isTrue();
    }

    @Test
    void stringEqualityIsValid() {
        FormulaValidationResult r = cond("LATEST(auditor, '6m') == 'KPMG'");
        assertThat(r.valid()).isTrue();
        assertThat(r.sampleResult()).isEqualTo("false");
    }

    @Test
    void dateComparisonIsValid() {
        assertThat(cond("LATEST(lastAuditDate, '1y') > DATE('2026-01-01')").valid()).isTrue();
    }

    @Test
    void booleanEqualityIsValid() {
        assertThat(cond("insuranceActive == TRUE").valid()).isTrue();
        assertThat(cond("insuranceActive == false").valid()).isTrue();
    }

    @Test
    void notAndArithmeticAndNegationAreValid() {
        assertThat(cond("NOT (DSCR > 5)").valid()).isTrue();
        assertThat(cond("DSCR + EBITDA > 3").valid()).isTrue();
        assertThat(cond("DSCR * 2 - 1 > 0").valid()).isTrue();
        assertThat(cond("-DSCR < 0").valid()).isTrue();
    }

    @Test
    void constantFormulaWithoutMeasuresIsValid() {
        FormulaValidationResult r = cond("1 > 0");
        assertThat(r.valid()).isTrue();
        assertThat(r.sampleResult()).isEqualTo("true");
    }

    @Test
    void valueFormulaAcceptsScalarResult() {
        FormulaValidationResult r = value("AVG(PAT, '2y')");
        assertThat(r.valid()).isTrue();
        assertThat(r.sampleResult()).isEqualTo("2.0");
    }

    @Test
    void caseInsensitiveKeywordsAndFunctions() {
        // Functions and keywords are case-insensitive; measure codes are case-sensitive.
        assertThat(cond("avg(PAT, '2y') > 1 and DSCR > 0 or not (EBITDA < 0)").valid()).isTrue();
    }

    // ---------------------------------------------------------------- empty / limits

    @Test
    void blankFormulaIsEmptyError() {
        assertThat(has(cond(""), ErrorCode.EMPTY_FORMULA)).isTrue();
        assertThat(has(cond("   "), ErrorCode.EMPTY_FORMULA)).isTrue();
        assertThat(has(engine.validate(null, catalog, true), ErrorCode.EMPTY_FORMULA)).isTrue();
    }

    @Test
    void tooLongFormulaIsRejected() {
        String formula = "1".repeat(FormulaEngine.MAX_LENGTH + 1);
        assertThat(has(cond(formula), ErrorCode.FORMULA_TOO_LONG)).isTrue();
    }

    @Test
    void tooManyTermsIsRejected() {
        String formula = IntStream.rangeClosed(1, FormulaEngine.MAX_TERMS + 1)
                .mapToObj(i -> "AVG(PAT, '" + i + "d') > 0")
                .collect(Collectors.joining(" AND "));
        assertThat(has(cond(formula), ErrorCode.TOO_MANY_TERMS)).isTrue();
    }

    @Test
    void tooDeepNestingIsRejected() {
        String formula = "(".repeat(33) + "DSCR > 1" + ")".repeat(33);
        assertThat(has(cond(formula), ErrorCode.TOO_DEEP)).isTrue();
    }

    // ---------------------------------------------------------------- lex errors

    @Test
    void unexpectedCharacterIsLexError() {
        FormulaValidationResult r = cond("DSCR @ 1");
        assertThat(has(r, ErrorCode.LEX_ERROR)).isTrue();
        assertThat(r.errors().get(0).startOffset()).isEqualTo(5);
    }

    @Test
    void singleEqualsIsLexError() {
        assertThat(has(cond("DSCR = 1"), ErrorCode.LEX_ERROR)).isTrue();
    }

    @Test
    void singleBangIsLexError() {
        assertThat(has(cond("DSCR ! 1"), ErrorCode.LEX_ERROR)).isTrue();
    }

    @Test
    void unterminatedStringIsLexError() {
        assertThat(has(cond("auditor == 'KPMG"), ErrorCode.LEX_ERROR)).isTrue();
    }

    @Test
    void escapedQuoteInStringIsValid() {
        assertThat(cond("auditor == 'O''Brien'").valid()).isTrue();
    }

    @Test
    void trailingDotAfterNumberIsLexError() {
        assertThat(has(cond("DSCR > 1."), ErrorCode.LEX_ERROR)).isTrue();
    }

    // ---------------------------------------------------------------- parse errors

    @Test
    void trailingOperatorIsParseError() {
        assertThat(has(cond("DSCR >"), ErrorCode.PARSE_ERROR)).isTrue();
    }

    @Test
    void unbalancedParenIsParseError() {
        assertThat(has(cond("(DSCR > 1"), ErrorCode.PARSE_ERROR)).isTrue();
    }

    @Test
    void trailingTokensAreParseError() {
        assertThat(has(cond("DSCR > 1 2"), ErrorCode.PARSE_ERROR)).isTrue();
    }

    @Test
    void chainedComparisonIsRejected() {
        assertThat(has(cond("DSCR > 1 > 0"), ErrorCode.CHAINED_COMPARISON)).isTrue();
    }

    @Test
    void unknownFunctionIsRejected() {
        assertThat(has(cond("AVERAGE(PAT, '2y') > 1"), ErrorCode.UNKNOWN_FUNCTION)).isTrue();
    }

    @Test
    void badArityIsRejected() {
        assertThat(has(cond("AVG(PAT) > 1"), ErrorCode.BAD_ARITY)).isTrue();
        // A fourth argument (after measure, window, sampling) is too many.
        assertThat(has(cond("AVG(PAT, '2y', PERIODIC, '3y') > 1"), ErrorCode.BAD_ARITY)).isTrue();
    }

    @Test
    void invalidWindowIsRejected() {
        assertThat(has(cond("AVG(PAT, '5x') > 1"), ErrorCode.INVALID_WINDOW)).isTrue();
        assertThat(has(cond("AVG(PAT, 2) > 1"), ErrorCode.INVALID_WINDOW)).isTrue();
        assertThat(has(cond("AVG(PAT, '0y') > 1"), ErrorCode.INVALID_WINDOW)).isTrue();
    }

    @Test
    void nonMeasureFirstArgumentIsArgTypeError() {
        assertThat(has(cond("AVG(2, '2y') > 1"), ErrorCode.ARG_TYPE)).isTrue();
    }

    @Test
    void invalidDateLiteralIsParseError() {
        assertThat(has(cond("lastAuditDate > DATE('not-a-date')"), ErrorCode.PARSE_ERROR)).isTrue();
        assertThat(has(cond("lastAuditDate > DATE(2026)"), ErrorCode.PARSE_ERROR)).isTrue();
    }

    // ---------------------------------------------------------------- type errors

    @Test
    void unknownMeasureSuggestsClosest() {
        FormulaValidationResult r = cond("DSCRR > 1");
        assertThat(has(r, ErrorCode.UNKNOWN_MEASURE)).isTrue();
        assertThat(r.errors().get(0).message()).contains("Did you mean 'DSCR'");
        assertThat(r.errors().get(0).startOffset()).isEqualTo(0);
        assertThat(r.errors().get(0).endOffset()).isEqualTo(5);
    }

    @Test
    void unknownMeasureWithoutCloseMatchHasNoSuggestion() {
        FormulaValidationResult r = cond("Zzzzzzz > 1");
        assertThat(has(r, ErrorCode.UNKNOWN_MEASURE)).isTrue();
        assertThat(r.errors().get(0).message()).doesNotContain("Did you mean");
    }

    @Test
    void aggregationOverNonNumericMeasureIsArgType() {
        assertThat(has(cond("AVG(auditor, '2y') > 1"), ErrorCode.ARG_TYPE)).isTrue();
    }

    @Test
    void incompatibleComparisonIsTypeMismatch() {
        assertThat(has(cond("auditor > 'x'"), ErrorCode.TYPE_MISMATCH_COMPARE)).isTrue();
        assertThat(has(cond("DSCR == auditor"), ErrorCode.TYPE_MISMATCH_COMPARE)).isTrue();
    }

    @Test
    void arithmeticOnNonNumberIsTypeMismatch() {
        assertThat(has(cond("insuranceActive + 1 > 0"), ErrorCode.TYPE_MISMATCH_ARITH)).isTrue();
        assertThat(has(cond("-auditor < 0"), ErrorCode.TYPE_MISMATCH_ARITH)).isTrue();
    }

    @Test
    void logicalOnNonBooleanIsTypeMismatch() {
        assertThat(has(cond("DSCR AND EBITDA"), ErrorCode.TYPE_MISMATCH_LOGICAL)).isTrue();
        assertThat(has(cond("NOT DSCR"), ErrorCode.TYPE_MISMATCH_LOGICAL)).isTrue();
    }

    @Test
    void nonBooleanConditionIsRejected() {
        assertThat(has(cond("DSCR"), ErrorCode.NON_BOOLEAN_CONDITION)).isTrue();
        assertThat(has(cond("DSCR + 1"), ErrorCode.NON_BOOLEAN_CONDITION)).isTrue();
    }

    @Test
    void valueFormulaDoesNotRequireBoolean() {
        assertThat(value("DSCR").valid()).isTrue();
        assertThat(value("AVG(PAT, '2y') + 1").valid()).isTrue();
    }

    @Test
    void multipleErrorsAreCollected() {
        FormulaValidationResult r = cond("BadOne > 1 AND BadTwo < 2");
        assertThat(r.errors()).filteredOn(e -> e.code() == ErrorCode.UNKNOWN_MEASURE).hasSize(2);
    }

    // ---------------------------------------------------------------- evaluation errors

    @Test
    void divisionByZeroIsEvalError() {
        assertThat(has(cond("DSCR / 0 > 1"), ErrorCode.EVAL_ERROR)).isTrue();
    }

    // ---------------------------------------------------------------- US-AGG-11 sampling (optional 3rd argument)

    @Test
    void allSamplingKeywordsParseAndValidate() {
        assertThat(cond("AVG(PAT, '2q', FLAT) > 1").valid()).isTrue();
        assertThat(cond("AVG(PAT, '2q', PERIODIC) > 1").valid()).isTrue();
        assertThat(cond("AVG(PAT, '2q', PERIODIC_CURRENT) > 1").valid()).isTrue();
        assertThat(cond("AVG(PAT, '2q', PERIODIC_COMPLETED) > 1").valid()).isTrue();
    }

    @Test
    void samplingKeywordIsCaseInsensitive() {
        assertThat(cond("avg(PAT, '2q', periodic) > 1").valid()).isTrue();
        assertThat(cond("AVG(PAT, '2q', Periodic_Current) > 1").valid()).isTrue();
    }

    @Test
    void explicitFlatSamplingMatchesTheDefault() {
        // FLAT is the default, so the explicit keyword resolves to the same sample value as omitting it.
        FormulaValidationResult withKeyword = value("AVG(PAT, '2y', FLAT)");
        FormulaValidationResult withoutKeyword = value("AVG(PAT, '2y')");
        assertThat(withKeyword.valid()).isTrue();
        assertThat(withoutKeyword.valid()).isTrue();
        assertThat(withKeyword.sampleResult()).isEqualTo(withoutKeyword.sampleResult());
    }

    @Test
    void unknownSamplingKeywordIsInvalidSampling() {
        assertThat(has(cond("AVG(PAT, '2q', WEEKLY) > 1"), ErrorCode.INVALID_SAMPLING)).isTrue();
    }

    @Test
    void quotedSamplingArgumentIsInvalidSampling() {
        // The sampling keyword is written bare (like a function name), not as a quoted string.
        assertThat(has(cond("AVG(PAT, '2q', 'PERIODIC') > 1"), ErrorCode.INVALID_SAMPLING)).isTrue();
    }

    @Test
    void periodicRequiresPeriodWindowForTrueAggregates() {
        // AC7: PERIODIC needs a month/quarter/year window; day/week windows are rejected.
        assertThat(has(cond("AVG(PAT, '90d', PERIODIC) > 1"), ErrorCode.INVALID_WINDOW)).isTrue();
        assertThat(has(cond("SUM(PAT, '4w', PERIODIC) > 1"), ErrorCode.INVALID_WINDOW)).isTrue();
        assertThat(cond("AVG(PAT, '6m', PERIODIC) > 1").valid()).isTrue();
        assertThat(cond("AVG(PAT, '3q', PERIODIC) > 1").valid()).isTrue();
        assertThat(cond("AVG(PAT, '2y', PERIODIC) > 1").valid()).isTrue();
    }

    @Test
    void periodicIsIgnoredForLatestAndCurrent() {
        // LATEST/CURRENT collapse to a single most-recent value, so PERIODIC is accepted and ignored —
        // even on a day window that PERIODIC would otherwise reject.
        assertThat(cond("LATEST(EBITDA, '90d', PERIODIC) > 2").valid()).isTrue();
        assertThat(cond("CURRENT(EBITDA, '90d', PERIODIC) > 2").valid()).isTrue();
    }

    // ---------------------------------------------------------------- security (former SpEL exploits are now plain errors)

    @Test
    void formerSpelExploitsAreOrdinaryErrors() {
        assertThat(cond("T(java.lang.Runtime).getRuntime()").valid()).isFalse();
        assertThat(cond("new String('x') == 'x'").valid()).isFalse();
        assertThat(cond("''.class.name == 'x'").valid()).isFalse();
        assertThat(cond("@bean.invoke()").valid()).isFalse();
    }
}
