package com.crisil.covenant.formula;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Direct tests for {@link FormulaTypeChecker} branches the parser cannot produce. The parser always
 * requires a window string, so a PERIODIC aggregation with a {@code null} window only arises if a
 * node is built programmatically; the checker still defends against it (AC7).
 */
class FormulaTypeCheckerTest {

    private final FormulaTypeChecker checker = new FormulaTypeChecker();
    private final MeasureCatalog catalog = FormulaTestSupport.catalog();

    @Test
    void periodicWithoutAWindowIsInvalidWindow() {
        Node.MeasureRef pat = new Node.MeasureRef("PAT", 0, 3);
        Node.Aggregation agg = new Node.Aggregation(FunctionKind.AVG, pat, null,
                SamplingMode.PERIODIC, PeriodInclusion.DEFAULT, 0, 10);
        List<FormulaError> errors = new ArrayList<>();

        checker.check(agg, catalog, errors);

        assertThat(errors).anyMatch(e -> e.code() == ErrorCode.INVALID_WINDOW
                && e.message().contains("a bare measure"));
    }
}
