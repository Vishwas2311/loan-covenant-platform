package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.enums.TableEntityKeyLevel;
import com.crisil.covenant.enums.TableLayout;
import com.crisil.covenant.formula.FunctionKind;
import com.crisil.covenant.formula.PeriodInclusion;
import com.crisil.covenant.formula.SamplingMode;
import com.crisil.covenant.formula.Window;
import com.crisil.covenant.formula.WindowUnit;
import com.crisil.covenant.service.MeasureAggregationResolver.AggregationRequest;
import com.crisil.covenant.service.MeasureAggregationResolver.MeasureValue;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import com.crisil.covenant.entity.Measure;

/**
 * Exercises the resolver against a seeded {@code cov_staging_value} store — the single physical
 * table every source is ingested into before assessment. The identity of a measure's rows is fully
 * captured by {@code (data_source_table_id, item_code)}, so the four logical datasets below live in
 * one table, distinguished by those two columns; the value sits in the typed column chosen by the
 * measure's {@link DataColumnType}. Identifier-validation/injection concerns moved to ingestion and
 * are covered by {@code StagingIngestionServiceTest}.
 */
class MeasureAggregationResolverTest {

    private static final String JDBC_URL =
            "jdbc:h2:mem:aggresolver;MODE=MSSQLServer;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 19);

    // Each logical dataset is a distinct data_source_table_id within the shared staging table.
    private static final String T_FIN = "tbl-fin";
    private static final String T_EAV = "tbl-eav";
    private static final String T_PERIODIC = "tbl-periodic";
    private static final String T_RATIOS_PERIODIC = "tbl-ratios-periodic";
    private static final String T_OBLIGOR = "tbl-obligor";

    private static final JdbcTemplate JDBC =
            new JdbcTemplate(new DriverManagerDataSource(JDBC_URL));

    private final ObligorMappingService obligorMapping = Mockito.mock(ObligorMappingService.class);
    private final MeasureAggregationResolver resolver =
            new MeasureAggregationResolver(JDBC, obligorMapping);

    @BeforeAll
    static void seed() {
        JDBC.execute("DROP TABLE IF EXISTS cov_staging_value");
        JDBC.execute("CREATE TABLE cov_staging_value ("
                + "id VARCHAR(36) NOT NULL, data_source_table_id VARCHAR(36) NOT NULL, "
                + "entity_key VARCHAR(128) NOT NULL, as_of_date DATE NOT NULL, "
                + "item_code VARCHAR(128) NOT NULL, num_value DECIMAL(38,6), "
                + "str_value VARCHAR(2000), date_value DATE, bool_value BIT, "
                + "data_type VARCHAR(16) NOT NULL)");

        // T_FIN — COLUMNAR: item_code is the physical column (pat / rating).
        num(T_FIN, "CP-1", "2024-01-01", "pat", 10);
        num(T_FIN, "CP-1", "2025-01-01", "pat", 20);
        num(T_FIN, "CP-1", "2026-01-01", "pat", 30);
        str(T_FIN, "CP-1", "2024-01-01", "rating", "A");
        str(T_FIN, "CP-1", "2025-01-01", "rating", "B");
        str(T_FIN, "CP-1", "2026-01-01", "rating", "C");
        num(T_FIN, "CP-2", "2026-01-01", "pat", 999);   // other entity — must not leak
        str(T_FIN, "CP-2", "2026-01-01", "rating", "Z");

        // T_EAV — KEY_VALUE: item_code is the code value (DSCR / PAT).
        num(T_EAV, "CP-1", "2024-01-01", "DSCR", 1.0);
        num(T_EAV, "CP-1", "2025-01-01", "DSCR", 2.0);
        num(T_EAV, "CP-1", "2026-01-01", "DSCR", 3.0);
        num(T_EAV, "CP-1", "2026-01-01", "PAT", 0.5);   // same entity/date, other code — must not leak
        num(T_EAV, "CP-2", "2026-01-01", "DSCR", 9.9);  // other entity — must not leak

        // T_PERIODIC — COLUMNAR quarterly series. As-of 2026-06-19: Q2-2026 (Apr–Jun) in-progress.
        // Period-ends: Q2'26=30, Q1'26=60, Q4'25=90, Q3'25=120; Q2-2025 intentionally empty.
        num(T_PERIODIC, "CP-1", "2025-08-15", "val", 8);
        num(T_PERIODIC, "CP-1", "2025-09-30", "val", 120);
        num(T_PERIODIC, "CP-1", "2025-10-15", "val", 7);
        num(T_PERIODIC, "CP-1", "2025-12-31", "val", 90);
        num(T_PERIODIC, "CP-1", "2026-01-15", "val", 6);
        num(T_PERIODIC, "CP-1", "2026-03-31", "val", 60);
        num(T_PERIODIC, "CP-1", "2026-04-15", "val", 5);
        num(T_PERIODIC, "CP-1", "2026-06-15", "val", 30);
        num(T_PERIODIC, "CP-2", "2026-06-01", "val", 777); // noise

        // T_RATIOS_PERIODIC — KEY_VALUE + PERIODIC: DSCR twice per quarter; a same-date PAT must not leak.
        num(T_RATIOS_PERIODIC, "CP-1", "2025-10-15", "DSCR", 2.0);
        num(T_RATIOS_PERIODIC, "CP-1", "2025-12-31", "DSCR", 5.0);
        num(T_RATIOS_PERIODIC, "CP-1", "2026-01-15", "DSCR", 1.0);
        num(T_RATIOS_PERIODIC, "CP-1", "2026-03-31", "DSCR", 3.0);
        num(T_RATIOS_PERIODIC, "CP-1", "2026-03-31", "PAT", 99.0);

        // T_OBLIGOR — COLUMNAR, obligor-keyed: rows are stored under an OBLIGOR id (not a CP id) and
        // shared across every CP of that obligor. leverage=42 is the obligor-grain figure to fan out.
        num(T_OBLIGOR, "OBL-1", "2026-01-01", "leverage", 42);
    }

    private static void num(String tableId, String entity, String date, String code, double value) {
        JDBC.update("INSERT INTO cov_staging_value "
                + "(id, data_source_table_id, entity_key, as_of_date, item_code, num_value, data_type) "
                + "VALUES (?,?,?,?,?,?,'number')",
                UUID.randomUUID().toString(), tableId, entity, java.sql.Date.valueOf(date), code, value);
    }

    private static void str(String tableId, String entity, String date, String code, String value) {
        JDBC.update("INSERT INTO cov_staging_value "
                + "(id, data_source_table_id, entity_key, as_of_date, item_code, str_value, data_type) "
                + "VALUES (?,?,?,?,?,?,'string')",
                UUID.randomUUID().toString(), tableId, entity, java.sql.Date.valueOf(date), code, value);
    }

    private DataSourceTable table(String id, TableLayout layout) {
        DataSourceTable t = new DataSourceTable();
        ReflectionTestUtils.setField(t, "id", id);
        t.setName("t");
        t.setLayout(layout);
        return t;
    }

    private Measure measure(String sourceColumn, DataColumnType type) {
        Measure m = new Measure();
        m.setCode(sourceColumn.toUpperCase());
        m.setDataSourceTable(table(T_FIN, TableLayout.COLUMNAR));
        m.setSourceColumn(sourceColumn);
        m.setDataType(type);
        return m;
    }

    private Measure keyValueMeasure(String sourceCode) {
        Measure m = new Measure();
        m.setCode(sourceCode);
        m.setDataSourceTable(table(T_EAV, TableLayout.KEY_VALUE));
        m.setSourceCode(sourceCode);
        m.setDataType(DataColumnType.NUMBER);
        return m;
    }

    private Measure periodicMeasure() {
        Measure m = new Measure();
        m.setCode("VAL");
        m.setDataSourceTable(table(T_PERIODIC, TableLayout.COLUMNAR));
        m.setSourceColumn("val");
        m.setDataType(DataColumnType.NUMBER);
        return m;
    }

    private Measure periodicKvMeasure(String sourceCode) {
        Measure m = new Measure();
        m.setCode(sourceCode);
        m.setDataSourceTable(table(T_RATIOS_PERIODIC, TableLayout.KEY_VALUE));
        m.setSourceCode(sourceCode);
        m.setDataType(DataColumnType.NUMBER);
        return m;
    }

    /** An obligor-keyed COLUMNAR measure: staging rows live under the CP's obligor, not the CP. */
    private Measure obligorMeasure() {
        DataSourceTable t = table(T_OBLIGOR, TableLayout.COLUMNAR);
        t.setEntityKeyLevel(TableEntityKeyLevel.OBLIGOR_ID);
        Measure m = new Measure();
        m.setCode("LEVERAGE");
        m.setDataSourceTable(t);
        m.setSourceColumn("leverage");
        m.setDataType(DataColumnType.NUMBER);
        return m;
    }

    private AggregationRequest periodicReq(FunctionKind fn, Window window, PeriodInclusion inclusion) {
        return new AggregationRequest(periodicMeasure(), fn, window,
                SamplingMode.PERIODIC, inclusion, "p");
    }

    private double periodicValue(AggregationRequest r, int fiscalStart, boolean includeCurrentDefault) {
        return periodicValue(r, AS_OF, fiscalStart, includeCurrentDefault);
    }

    private double periodicValue(AggregationRequest r, LocalDate asOf, int fiscalStart,
                                 boolean includeCurrentDefault) {
        MeasureValue v = resolver.resolve("CP-1", asOf, List.of(r), fiscalStart, includeCurrentDefault)
                .get(r.valueKey());
        assertThat(v.present()).isTrue();
        return ((Number) v.value()).doubleValue();
    }

    private AggregationRequest req(FunctionKind fn, Window window) {
        String key = (fn == null ? "BARE" : fn.name()) + (window == null ? "" : window.canonical());
        return new AggregationRequest(measure("pat", DataColumnType.NUMBER), fn, window, key);
    }

    private double value(FunctionKind fn, Window window) {
        AggregationRequest r = req(fn, window);
        MeasureValue v = resolver.resolve("CP-1", AS_OF, List.of(r)).get(r.valueKey());
        assertThat(v.present()).isTrue();
        return ((Number) v.value()).doubleValue();
    }

    @Test
    void latestOlderThanMaxAgeCountsAsMissing() {
        Measure stale = measure("pat", DataColumnType.NUMBER);
        stale.setMaxAgeDays(100); // latest pat is 2026-01-01, 169 days before AS_OF
        AggregationRequest r = new AggregationRequest(stale, FunctionKind.LATEST, null, "stale");
        assertThat(resolver.resolve("CP-1", AS_OF, List.of(r)).get("stale").present()).isFalse();

        Measure fresh = measure("pat", DataColumnType.NUMBER);
        fresh.setMaxAgeDays(400);
        AggregationRequest ok = new AggregationRequest(fresh, FunctionKind.LATEST, null, "fresh");
        assertThat(((Number) resolver.resolve("CP-1", AS_OF, List.of(ok)).get("fresh").value()).doubleValue())
                .isEqualTo(30.0);
    }

    @Test
    void averageOverFiveYears() {
        assertThat(value(FunctionKind.AVG, new Window(5, WindowUnit.Y))).isEqualTo(20.0);
    }

    @Test
    void averageOverOneYearWindowsOutOlderRows() {
        // Only the 2026-01-01 row falls within the last year of 2026-06-19.
        assertThat(value(FunctionKind.AVG, new Window(1, WindowUnit.Y))).isEqualTo(30.0);
    }

    @Test
    void sumMinMaxCountOverFiveYears() {
        assertThat(value(FunctionKind.SUM, new Window(5, WindowUnit.Y))).isEqualTo(60.0);
        assertThat(value(FunctionKind.MIN, new Window(5, WindowUnit.Y))).isEqualTo(10.0);
        assertThat(value(FunctionKind.MAX, new Window(5, WindowUnit.Y))).isEqualTo(30.0);
        assertThat(value(FunctionKind.COUNT, new Window(5, WindowUnit.Y))).isEqualTo(3.0);
    }

    @Test
    void latestReturnsMostRecentWithinWindow() {
        assertThat(value(FunctionKind.LATEST, new Window(5, WindowUnit.Y))).isEqualTo(30.0);
        assertThat(value(FunctionKind.CURRENT, new Window(5, WindowUnit.Y))).isEqualTo(30.0);
    }

    @Test
    void bareMeasureReturnsLatestAsOf() {
        assertThat(value(null, null)).isEqualTo(30.0);
    }

    @Test
    void nonNumericLatestReturnsTypedValue() {
        AggregationRequest r = new AggregationRequest(
                measure("rating", DataColumnType.STRING), FunctionKind.LATEST,
                new Window(5, WindowUnit.Y), "rating");
        MeasureValue v = resolver.resolve("CP-1", AS_OF, List.of(r)).get("rating");
        assertThat(v.present()).isTrue();
        assertThat(v.value()).isEqualTo("C");
    }

    @Test
    void aggregateOverEmptyWindowIsAbsent() {
        AggregationRequest r = req(FunctionKind.AVG, new Window(1, WindowUnit.D));
        MeasureValue v = resolver.resolve("CP-1", AS_OF, List.of(r)).get(r.valueKey());
        assertThat(v.present()).isFalse();
        assertThat(v.value()).isNull();
    }

    @Test
    void latestOverEmptyWindowIsAbsent() {
        AggregationRequest r = req(FunctionKind.LATEST, new Window(1, WindowUnit.D));
        MeasureValue v = resolver.resolve("CP-1", AS_OF, List.of(r)).get(r.valueKey());
        assertThat(v.present()).isFalse();
    }

    @Test
    void resolvesMultipleTermsForOneEntity() {
        AggregationRequest avg = req(FunctionKind.AVG, new Window(5, WindowUnit.Y));
        AggregationRequest max = req(FunctionKind.MAX, new Window(5, WindowUnit.Y));
        Map<String, MeasureValue> values = resolver.resolve("CP-1", AS_OF, List.of(avg, max));
        assertThat(values).hasSize(2);
        assertThat(((Number) values.get(avg.valueKey()).value()).doubleValue()).isEqualTo(20.0);
        assertThat(((Number) values.get(max.valueKey()).value()).doubleValue()).isEqualTo(30.0);
    }

    @Test
    void entityKeyIsolatesRows() {
        // CP-2's pat=999 must never surface for CP-1 even without a code/column difference.
        AggregationRequest r = req(FunctionKind.MAX, new Window(5, WindowUnit.Y));
        MeasureValue v = resolver.resolve("CP-1", AS_OF, List.of(r)).get(r.valueKey());
        assertThat(((Number) v.value()).doubleValue()).isEqualTo(30.0);
    }

    @Test
    void keyValueLatestResolvesByCode() {
        AggregationRequest r = new AggregationRequest(
                keyValueMeasure("DSCR"), FunctionKind.LATEST, new Window(5, WindowUnit.Y), "dscr");
        MeasureValue v = resolver.resolve("CP-1", AS_OF, List.of(r)).get("dscr");
        assertThat(v.present()).isTrue();
        assertThat(((Number) v.value()).doubleValue()).isEqualTo(3.0);
    }

    @Test
    void keyValueAvgAggregatesOnlyMatchingCode() {
        // Averages the three DSCR rows (1,2,3) only — the same-date PAT row must be filtered out.
        AggregationRequest r = new AggregationRequest(
                keyValueMeasure("DSCR"), FunctionKind.AVG, new Window(5, WindowUnit.Y), "dscr");
        MeasureValue v = resolver.resolve("CP-1", AS_OF, List.of(r)).get("dscr");
        assertThat(v.present()).isTrue();
        assertThat(((Number) v.value()).doubleValue()).isEqualTo(2.0);
    }

    @Test
    void keyValueMissingCodeIsAbsent() {
        AggregationRequest r = new AggregationRequest(
                keyValueMeasure("XIRR"), FunctionKind.LATEST, new Window(5, WindowUnit.Y), "xirr");
        MeasureValue v = resolver.resolve("CP-1", AS_OF, List.of(r)).get("xirr");
        assertThat(v.present()).isFalse();
        assertThat(v.value()).isNull();
    }

    // --- US-DS onboarding: obligor-keyed tables (entity_key_level = OBLIGOR_ID) ---

    @Test
    void obligorKeyedTableBindsResolvedObligorAsEntityKey() {
        // The CP resolves to OBL-1; the reading lives under the obligor key, not the CP key.
        Mockito.when(obligorMapping.obligorFor("CP-1")).thenReturn(Optional.of("OBL-1"));
        AggregationRequest r = new AggregationRequest(
                obligorMeasure(), FunctionKind.LATEST, new Window(5, WindowUnit.Y), "lev");
        MeasureValue v = resolver.resolve("CP-1", AS_OF, List.of(r)).get("lev");
        assertThat(v.present()).isTrue();
        assertThat(((Number) v.value()).doubleValue()).isEqualTo(42.0);
    }

    @Test
    void obligorKeyedTableFansSharedDataToEveryCpOfTheObligor() {
        // Two different CPs of OBL-1 read the same obligor-grain rows — the fan-out at read time.
        Mockito.when(obligorMapping.obligorFor("CP-1")).thenReturn(Optional.of("OBL-1"));
        Mockito.when(obligorMapping.obligorFor("CP-2")).thenReturn(Optional.of("OBL-1"));
        AggregationRequest r = new AggregationRequest(
                obligorMeasure(), FunctionKind.LATEST, new Window(5, WindowUnit.Y), "lev");
        double cp1 = ((Number) resolver.resolve("CP-1", AS_OF, List.of(r)).get("lev").value()).doubleValue();
        double cp2 = ((Number) resolver.resolve("CP-2", AS_OF, List.of(r)).get("lev").value()).doubleValue();
        assertThat(cp1).isEqualTo(42.0);
        assertThat(cp2).isEqualTo(42.0);
    }

    @Test
    void obligorKeyedTableWithNoMappingIsAbsentNeverAWrongEntityRead() {
        // No recorded obligor ⇒ a clean "missing", never a fall-through to the CP-keyed rows.
        Mockito.when(obligorMapping.obligorFor("CP-404")).thenReturn(Optional.empty());
        AggregationRequest r = new AggregationRequest(
                obligorMeasure(), FunctionKind.LATEST, new Window(5, WindowUnit.Y), "lev");
        MeasureValue v = resolver.resolve("CP-404", AS_OF, List.of(r)).get("lev");
        assertThat(v.present()).isFalse();
        assertThat(v.value()).isNull();
    }

    @Test
    void cpKeyedTableNeverConsultsObligorMapping() {
        // A CP-keyed table (the default) must incur no obligor lookup at all.
        AggregationRequest r = req(FunctionKind.LATEST, new Window(5, WindowUnit.Y));
        resolver.resolve("CP-1", AS_OF, List.of(r));
        Mockito.verify(obligorMapping, Mockito.never()).obligorFor(Mockito.anyString());
    }

    // --- US-AGG-11: PERIODIC (period-end) sampling ---

    @Test
    void periodicQuarterlyAveragesPeriodEndsNotEveryReading() {
        // Q2-2026(30), Q1-2026(60), Q4-2025(90) period-ends -> 60; a flat AVG over the same window
        // (which counts every reading, including the mid-quarter noise) is markedly lower.
        AggregationRequest periodic =
                periodicReq(FunctionKind.AVG, new Window(3, WindowUnit.Q), PeriodInclusion.DEFAULT);
        assertThat(periodicValue(periodic, 1, true)).isEqualTo(60.0);

        AggregationRequest flat = new AggregationRequest(periodicMeasure(), FunctionKind.AVG,
                new Window(3, WindowUnit.Q), SamplingMode.FLAT, PeriodInclusion.DEFAULT, "f");
        MeasureValue flatValue = resolver.resolve("CP-1", AS_OF, List.of(flat), 1, true).get("f");
        assertThat(((Number) flatValue.value()).doubleValue()).isCloseTo(45.43, within(0.01));
    }

    @Test
    void periodicIncludeCurrentKeywordOverridesOrgCompletedDefault() {
        // Org default = completed-only (false); the keyword forces include-current -> counts Q2-2026.
        AggregationRequest r =
                periodicReq(FunctionKind.AVG, new Window(3, WindowUnit.Q), PeriodInclusion.INCLUDE_CURRENT);
        assertThat(periodicValue(r, 1, false)).isEqualTo(60.0);
    }

    @Test
    void periodicCompletedOnlyKeywordExcludesInProgressQuarter() {
        // Completed-only: newest completed quarter is Q1-2026 -> Q1(60), Q4'25(90), Q3'25(120) -> 90.
        AggregationRequest r =
                periodicReq(FunctionKind.AVG, new Window(3, WindowUnit.Q), PeriodInclusion.COMPLETED_ONLY);
        assertThat(periodicValue(r, 1, true)).isEqualTo(90.0);
    }

    @Test
    void periodicDefaultFollowsOrgCompletedPolicy() {
        // No keyword (DEFAULT) + org policy = completed-only -> same result as the completed-only keyword.
        AggregationRequest r =
                periodicReq(FunctionKind.AVG, new Window(3, WindowUnit.Q), PeriodInclusion.DEFAULT);
        assertThat(periodicValue(r, 1, false)).isEqualTo(90.0);
    }

    @Test
    void periodicCompletedOnlyCountsAsOfWhenItIsThePeriodEnd() {
        // As-of exactly on a quarter end (2026-03-31): that quarter is complete and is the newest.
        // Q1-2026(60) + Q4-2025(90) -> 75.
        AggregationRequest r =
                periodicReq(FunctionKind.AVG, new Window(2, WindowUnit.Q), PeriodInclusion.COMPLETED_ONLY);
        assertThat(periodicValue(r, LocalDate.of(2026, 3, 31), 1, true)).isEqualTo(75.0);
    }

    @Test
    void periodicSkipsEmptyPeriods() {
        // 5 quarters requested but Q2-2025 has no rows -> average only the 4 present period-ends.
        AggregationRequest r =
                periodicReq(FunctionKind.AVG, new Window(5, WindowUnit.Q), PeriodInclusion.INCLUDE_CURRENT);
        assertThat(periodicValue(r, 1, true)).isEqualTo(75.0); // (30+60+90+120)/4
    }

    @Test
    void periodicMonthlyPeriodEnds() {
        // 3 months (Jun, May, Apr 2026); May is empty -> Jun(30) + Apr(5) -> 17.5.
        AggregationRequest r =
                periodicReq(FunctionKind.AVG, new Window(3, WindowUnit.M), PeriodInclusion.INCLUDE_CURRENT);
        assertThat(periodicValue(r, 1, true)).isEqualTo(17.5);
    }

    @Test
    void periodicYearlyHonoursCalendarVsFiscalStart() {
        AggregationRequest yearly =
                periodicReq(FunctionKind.AVG, new Window(2, WindowUnit.Y), PeriodInclusion.INCLUDE_CURRENT);
        // Calendar year (start month 1): 2026 end(30), 2025 end(90) -> 60.
        assertThat(periodicValue(yearly, 1, true)).isEqualTo(60.0);
        // Fiscal year (start month 4 = Apr): FY2026 end(30); FY2025 spans Apr'25–Mar'26 so its
        // period-end is 2026-03-31=60 -> 45. The fiscal shift changes which readings fall in a year.
        assertThat(periodicValue(yearly, 4, true)).isEqualTo(45.0);
    }

    @Test
    void periodicMaxUsesPeriodEndValues() {
        AggregationRequest r =
                periodicReq(FunctionKind.MAX, new Window(3, WindowUnit.Q), PeriodInclusion.COMPLETED_ONLY);
        assertThat(periodicValue(r, 1, true)).isEqualTo(120.0);
    }

    @Test
    void periodicKeyValueAveragesPeriodEndsFilteringByCode() {
        // Completed-only 2Q on the EAV table: Q1-2026 end DSCR=3, Q4-2025 end DSCR=5 -> 4.0.
        // The same-date PAT=99 row must be filtered out, else the Q1 period-end would be wrong.
        AggregationRequest r = new AggregationRequest(periodicKvMeasure("DSCR"), FunctionKind.AVG,
                new Window(2, WindowUnit.Q), SamplingMode.PERIODIC, PeriodInclusion.COMPLETED_ONLY, "kv");
        assertThat(periodicValue(r, 1, true)).isEqualTo(4.0);
    }

    @Test
    void periodicIgnoredForLatestBehavesPointInTime() {
        // LATEST + PERIODIC is a no-op: returns the single latest reading, no per-period collapse.
        AggregationRequest r =
                periodicReq(FunctionKind.LATEST, new Window(5, WindowUnit.Y), PeriodInclusion.DEFAULT);
        assertThat(periodicValue(r, 1, true)).isEqualTo(30.0);
    }

    @Test
    void periodicFallsBackToFlatForNonPeriodWindow() {
        // A week window is not period-capable, so PERIODIC degrades to a flat aggregate over the window.
        AggregationRequest r =
                periodicReq(FunctionKind.AVG, new Window(2, WindowUnit.W), PeriodInclusion.DEFAULT);
        assertThat(periodicValue(r, 1, true)).isEqualTo(30.0); // only 2026-06-15=30 in the last 2 weeks
    }

    @Test
    void periodicWithoutWindowFallsBackToFlat() {
        // Defensive: PERIODIC on an aggregation with no window degrades to a flat aggregate over all rows.
        AggregationRequest r = new AggregationRequest(periodicMeasure(), FunctionKind.MAX, null,
                SamplingMode.PERIODIC, PeriodInclusion.DEFAULT, "nw");
        assertThat(periodicValue(r, 1, true)).isEqualTo(120.0); // max over all CP-1 rows
    }
}
