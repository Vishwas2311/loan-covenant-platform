package com.crisil.covenant.service;

import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.entity.Measure;
import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.enums.TableEntityKeyLevel;
import com.crisil.covenant.enums.TableLayout;
import com.crisil.covenant.formula.FunctionKind;
import com.crisil.covenant.formula.PeriodInclusion;
import com.crisil.covenant.formula.SamplingMode;
import com.crisil.covenant.formula.Window;
import com.crisil.covenant.formula.WindowUnit;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolves measure terms to values by running windowed aggregate queries against the durable
 * {@code cov_staging_value} store — a single physical table into which every source (MSSQL or REST)
 * is ingested before assessment. For one entity (CP / customer / account) and an as-of date, each
 * term becomes a parameterised
 * {@code SELECT <AGG>(<value col>) FROM cov_staging_value
 *        WHERE data_source_table_id = ? AND entity_key = ? AND item_code = ? AND as_of_date BETWEEN ? AND ?}.
 *
 * <p>Because staging is uniform, both table layouts collapse to one predicate: the {@code item_code}
 * is the measure's {@code sourceColumn} (COLUMNAR) or {@code sourceCode} (KEY_VALUE), and the value
 * lives in the typed column selected by the measure's {@link DataColumnType}. A bare measure (no
 * aggregation) resolves to its latest value at or before the as-of date.
 *
 * <p>Covenants always execute at CP grain, but a table may be keyed at CP or obligor level
 * ({@link TableEntityKeyLevel}). For an obligor-keyed table the CP's obligor is resolved once per
 * {@code resolve} call and bound as {@code entity_key}, so obligor-grain data fans out to every CP of
 * that obligor; a CP with no recorded obligor yields a clean {@code present=false} (never a
 * wrong-entity read).
 */
@Component
public class MeasureAggregationResolver {

    /** The physical as-of column in the staging store (fixed, unlike the per-source live column). */
    private static final String AS_OF = "as_of_date";

    private final JdbcTemplate jdbcTemplate;
    private final ObligorMappingService obligorMappingService;

    public MeasureAggregationResolver(JdbcTemplate jdbcTemplate,
                                      ObligorMappingService obligorMappingService) {
        this.jdbcTemplate = jdbcTemplate;
        this.obligorMappingService = obligorMappingService;
    }

    /**
     * A measure term to resolve: the measure, an optional aggregation + window, the US-AGG-11
     * sampling choice ({@code sampling} + {@code inclusion}), and its value key.
     */
    public record AggregationRequest(Measure measure, FunctionKind aggregation, Window window,
                                     SamplingMode sampling, PeriodInclusion inclusion, String valueKey) {

        /** Convenience constructor for a FLAT term (bare measure or legacy aggregation). */
        public AggregationRequest(Measure measure, FunctionKind aggregation, Window window, String valueKey) {
            this(measure, aggregation, window, SamplingMode.FLAT, PeriodInclusion.DEFAULT, valueKey);
        }
    }

    /** The resolved value for a term. {@code present} is false when no data exists in the window. */
    public record MeasureValue(String valueKey, Object value, boolean present) {
    }

    /**
     * Resolve all terms for one entity at the given as-of date under the default reporting calendar
     * (calendar year, include the in-progress period). FLAT terms ignore these settings entirely.
     */
    public Map<String, MeasureValue> resolve(String entityKeyValue, LocalDate asOf,
                                             List<AggregationRequest> terms) {
        return resolve(entityKeyValue, asOf, terms, 1, true);
    }

    /**
     * Resolve all terms for one entity at the given as-of date. {@code fiscalYearStartMonth} (1-12)
     * and {@code periodicIncludesCurrentPeriod} are the organisation reporting-calendar settings
     * (US-AGG-11); they only affect PERIODIC terms whose inclusion is {@code DEFAULT}.
     */
    public Map<String, MeasureValue> resolve(String entityKeyValue, LocalDate asOf,
                                             List<AggregationRequest> terms,
                                             int fiscalYearStartMonth,
                                             boolean periodicIncludesCurrentPeriod) {
        // An obligor-keyed table reads staging rows landed at the CP's *obligor* grain. Resolve the
        // obligor for this CP at most once per call (only when some term actually needs it); a CP with
        // no recorded obligor stays empty and yields present=false for those terms.
        Optional<String> obligorId = resolveObligorIfNeeded(entityKeyValue, terms);
        Map<String, MeasureValue> out = new LinkedHashMap<>();
        for (AggregationRequest term : terms) {
            out.put(term.valueKey(), resolveOne(term, entityKeyValue, obligorId, asOf,
                    fiscalYearStartMonth, periodicIncludesCurrentPeriod));
        }
        return out;
    }

    /**
     * The obligor for {@code cpId}, resolved only when at least one term is backed by an obligor-keyed
     * table (so CP-only formulas incur no extra lookup). An empty result — no obligor-keyed term, or an
     * obligor-keyed term for a CP with no recorded mapping — leaves obligor terms {@code present=false}.
     */
    private Optional<String> resolveObligorIfNeeded(String cpId, List<AggregationRequest> terms) {
        boolean needed = terms.stream().anyMatch(term -> {
            DataSourceTable table = term.measure().getDataSourceTable();
            return table != null && table.getEntityKeyLevel() == TableEntityKeyLevel.OBLIGOR_ID;
        });
        return needed ? obligorMappingService.obligorFor(cpId) : Optional.empty();
    }

    @SuppressFBWarnings(
            value = "SQL_INJECTION_SPRING_JDBC",
            justification = "The query targets the fixed cov_staging_value table with fixed column "
                    + "names; the only interpolated identifiers are the value column (from a closed "
                    + "enum switch on DataColumnType), the aggregate function name (a closed enum "
                    + "switch), and the PERIODIC period-key expression (which interpolates only the "
                    + "fixed as_of_date column and a 0-11 integer fiscal-month offset derived from a "
                    + "server-side setting). Every data value — the data-source-table id, entity key, "
                    + "measure item code and window/period bounds — is bound as a '?' parameter. "
                    + "FindSecBugs cannot prove the dynamic SQL is safe; the reasoning above does.")
    private MeasureValue resolveOne(AggregationRequest term, String entityKeyValue,
                                    Optional<String> obligorId, LocalDate asOf,
                                    int fiscalYearStartMonth, boolean periodicIncludesCurrentPeriod) {
        Measure measure = term.measure();
        DataSourceTable table = measure.getDataSourceTable();

        // Pick the entity key this table's staging rows are stored under. CP-keyed tables read at the
        // CP grain; obligor-keyed tables read at the CP's obligor grain (shared across the obligor's
        // CPs). No recorded obligor ⇒ a clean "missing", never a wrong-entity read.
        String entityKey;
        if (table.getEntityKeyLevel() == TableEntityKeyLevel.OBLIGOR_ID) {
            if (obligorId.isEmpty()) {
                return new MeasureValue(term.valueKey(), null, false);
            }
            entityKey = obligorId.get();
        } else {
            entityKey = entityKeyValue;
        }

        // The measure→storage mapping is uniform in staging: item_code identifies the rows (physical
        // column for COLUMNAR, code value for KEY_VALUE), and the value is in the typed column chosen
        // by the measure's declared data type. Both are bound as parameters / chosen from an enum, so
        // no SQL-identifier validation of user input is needed on the read path.
        boolean keyValue = table.getLayout() == TableLayout.KEY_VALUE;
        String itemCode = keyValue ? measure.getSourceCode() : measure.getSourceColumn();
        String valueColumn = valueColumn(measure.getDataType());

        FunctionKind aggregation = term.aggregation() == null ? FunctionKind.LATEST : term.aggregation();
        boolean pointInTime = aggregation == FunctionKind.LATEST || aggregation == FunctionKind.CURRENT;
        // PERIODIC only applies to the true aggregates over a month/quarter/year window; it is a
        // no-op for LATEST/CURRENT (a single value) — the type checker already blocks D/W windows.
        boolean periodic = term.sampling() == SamplingMode.PERIODIC && !pointInTime
                && term.window() != null && term.window().unit().isPeriodCapable();

        LocalDate lowerBound;
        LocalDate upperBound;
        String periodKeyExpr = null;
        if (periodic) {
            boolean includeCurrent =
                    effectiveIncludeCurrent(term.inclusion(), periodicIncludesCurrentPeriod);
            PeriodRange range = periodRange(asOf, term.window(), fiscalYearStartMonth, includeCurrent);
            lowerBound = range.lower();
            upperBound = range.upper();
            periodKeyExpr = periodKeyExpr(term.window().unit(), AS_OF, fiscalYearStartMonth);
        } else {
            lowerBound = term.window() == null ? null : term.window().startFrom(asOf);
            upperBound = asOf;
            // T9: a LATEST/CURRENT read without a window may not reach further back than the measure allows.
            if (pointInTime && lowerBound == null && measure.getMaxAgeDays() != null) {
                lowerBound = asOf.minusDays(measure.getMaxAgeDays());
            }
        }

        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(
                " WHERE data_source_table_id = ? AND entity_key = ? AND item_code = ?");
        args.add(table.getId());
        args.add(entityKey);
        args.add(itemCode);
        if (lowerBound != null) {
            where.append(" AND ").append(AS_OF).append(" >= ?");
            args.add(java.sql.Date.valueOf(lowerBound));
        }
        where.append(" AND ").append(AS_OF).append(" <= ?");
        args.add(java.sql.Date.valueOf(upperBound));

        String sql;
        if (pointInTime) {
            sql = "SELECT TOP 1 " + valueColumn + " FROM cov_staging_value" + where
                    + " ORDER BY " + AS_OF + " DESC";
        } else if (periodic) {
            // Collapse each period to its period-end (latest-in-period) row via ROW_NUMBER, then
            // aggregate those per-period figures. Empty periods produce no row and are skipped (AC6).
            String inner = "SELECT " + valueColumn + " AS v, ROW_NUMBER() OVER (PARTITION BY " + periodKeyExpr
                    + " ORDER BY " + AS_OF + " DESC) AS rn FROM cov_staging_value" + where;
            sql = "SELECT " + sqlAggregate(aggregation, "v") + " FROM (" + inner + ") t WHERE t.rn = 1";
        } else {
            sql = "SELECT " + sqlAggregate(aggregation, valueColumn) + " FROM cov_staging_value" + where;
        }

        List<Object> rows = jdbcTemplate.query(sql, args.toArray(), (rs, rowNum) -> coerce(rs.getObject(1)));
        if (rows.isEmpty()) {
            return new MeasureValue(term.valueKey(), null, false);
        }
        Object value = rows.get(0);
        return new MeasureValue(term.valueKey(), value, value != null);
    }

    /** The staging column that holds a measure's value for its declared type. */
    private static String valueColumn(DataColumnType type) {
        return switch (type) {
            case NUMBER -> "num_value";
            case DATE -> "date_value";
            case BOOLEAN -> "bool_value";
            case STRING -> "str_value";
        };
    }

    /** The formula override wins; {@code DEFAULT} falls back to the organisation policy. */
    private static boolean effectiveIncludeCurrent(PeriodInclusion inclusion, boolean orgDefault) {
        return switch (inclusion) {
            case INCLUDE_CURRENT -> true;
            case COMPLETED_ONLY -> false;
            case DEFAULT -> orgDefault;
        };
    }

    /** The inclusive date range covering the N reporting periods a PERIODIC window selects. */
    private record PeriodRange(LocalDate lower, LocalDate upper) {
    }

    /**
     * Compute the [lower, upper] date range covering the N most recent reporting periods, honouring
     * the fiscal-year start and whether the in-progress period counts. The range is bounded so that
     * exactly those N periods (and no older/newer) can appear in the windowed query.
     */
    private static PeriodRange periodRange(LocalDate asOf, Window window, int fiscalYearStartMonth,
                                           boolean includeCurrent) {
        int monthsPerPeriod = monthsPerPeriod(window.unit());
        LocalDate anchorStart = periodStart(asOf, window.unit(), fiscalYearStartMonth);
        LocalDate anchorEnd = anchorStart.plusMonths(monthsPerPeriod).minusDays(1);
        LocalDate newestStart;
        LocalDate upper;
        if (includeCurrent || asOf.isEqual(anchorEnd)) {
            // The as-of date's own period is the newest; never read rows dated after the as-of date.
            newestStart = anchorStart;
            upper = asOf;
        } else {
            // Completed-only: the newest period is the one immediately before the in-progress period.
            newestStart = anchorStart.minusMonths(monthsPerPeriod);
            upper = anchorStart.minusDays(1);
        }
        LocalDate lower = newestStart.minusMonths((long) monthsPerPeriod * (window.amount() - 1));
        return new PeriodRange(lower, upper);
    }

    /**
     * The first calendar day of the reporting period that contains {@code asOf}. Only ever called
     * for period-capable units (M/Q/Y); months are always calendar months, quarters and years honour
     * the fiscal-year start.
     */
    private static LocalDate periodStart(LocalDate asOf, WindowUnit unit, int fiscalYearStartMonth) {
        if (unit == WindowUnit.M) {
            return asOf.withDayOfMonth(1);
        }
        long shift = fiscalYearStartMonth - 1L; // months to shift so the fiscal year aligns to Jan
        LocalDate shifted = asOf.minusMonths(shift);
        if (unit == WindowUnit.Q) {
            int quarterStartMonth = ((shifted.getMonthValue() - 1) / 3) * 3 + 1;
            return LocalDate.of(shifted.getYear(), quarterStartMonth, 1).plusMonths(shift);
        }
        return LocalDate.of(shifted.getYear(), 1, 1).plusMonths(shift); // Y
    }

    private static int monthsPerPeriod(WindowUnit unit) {
        if (unit == WindowUnit.M) {
            return 1;
        }
        return unit == WindowUnit.Q ? 3 : 12; // Q or Y
    }

    /**
     * A SQL expression that buckets each row's as-of date into a reporting period (constant within a
     * period, distinct across periods). Quarters/years honour the fiscal-year start by shifting the
     * date back {@code fiscalYearStartMonth - 1} months; months are always calendar months. The only
     * interpolated values are the fixed as-of column and a 0-11 integer offset. Only ever called for
     * period-capable units (M/Q/Y).
     */
    private static String periodKeyExpr(WindowUnit unit, String asOfColumn, int fiscalYearStartMonth) {
        if (unit == WindowUnit.M) {
            return "(YEAR(" + asOfColumn + ") * 12 + MONTH(" + asOfColumn + "))";
        }
        int offset = fiscalYearStartMonth - 1;
        String shifted = offset == 0
                ? asOfColumn
                : "DATEADD(MONTH, " + (-offset) + ", " + asOfColumn + ")";
        if (unit == WindowUnit.Q) {
            return "(YEAR(" + shifted + ") * 4 + (MONTH(" + shifted + ") - 1) / 3)";
        }
        return "YEAR(" + shifted + ")"; // Y
    }

    private static String sqlAggregate(FunctionKind aggregation, String column) {
        if (aggregation == FunctionKind.AVG) {
            return "AVG(CAST(" + column + " AS DECIMAL(38,6)))";
        }
        if (aggregation == FunctionKind.SUM) {
            return "SUM(CAST(" + column + " AS DECIMAL(38,6)))";
        }
        if (aggregation == FunctionKind.MIN) {
            return "MIN(" + column + ")";
        }
        if (aggregation == FunctionKind.MAX) {
            return "MAX(" + column + ")";
        }
        return "COUNT(" + column + ")";
    }

    /** Normalise JDBC types to the shapes the formula evaluator expects. */
    private static Object coerce(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal d) {
            return d.doubleValue();
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate();
        }
        return value;
    }
}
