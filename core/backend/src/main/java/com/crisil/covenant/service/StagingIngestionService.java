package com.crisil.covenant.service;

import com.crisil.covenant.domain.DataPoint;
import com.crisil.covenant.domain.DataSeriesResponse;
import com.crisil.covenant.domain.RestEndpoint;
import com.crisil.covenant.entity.DataSource;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.entity.Measure;
import com.crisil.covenant.entity.StagingValue;
import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.enums.TableLayout;
import com.crisil.covenant.repository.MeasureRepository;
import com.crisil.covenant.repository.StagingValueRepository;
import com.crisil.covenant.util.JdbcDataSourceRegistry;
import com.crisil.covenant.util.SqlIdentifiers;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lands a durable copy of a {@link DataSourceTable}'s data into {@code cov_staging_value} so
 * covenants evaluate against a persisted mirror rather than the live source. Both source kinds flow
 * through here:
 * <ul>
 *   <li>{@code MSSQL} — the entity's rows are copied straight from the live table over JDBC. A
 *       <em>windowed</em> term ({@code from} strictly before {@code asOf}) copies the incremental
 *       range {@code [effectiveFrom, asOf]} — only the dates newer than the staged watermark (the full
 *       window on the first run, or a reach-back refetch when a widened look-back needs older dates).
 *       A <em>point-in-time</em> term ({@code from == asOf} / {@code null}) copies just the latest
 *       row(s) {@code <= asOf} (per KEY_VALUE code), mirroring the REST snapshot below.</li>
 *   <li>{@code REST} — both terms hit the one as-of-aware endpoint, routed to the source's own
 *       {@code baseUrl}/{@code valuesPath} ({@link RestEndpoint}). A point-in-time term ({@code from ==
 *       asOf} / {@code null}) requests the single latest point {@code <= asOf}; a windowed term requests
 *       a series over the same incremental range. Earlier points on other dates are left in place so
 *       history accrues.</li>
 * </ul>
 *
 * <p>Ingestion is idempotent: within one transaction it deletes any prior copy for the entity across
 * exactly the as-of dates it re-reads, then inserts the fresh rows — so re-running a day overwrites
 * rather than duplicates (the {@code uk_staging_value} unique key is the anchor). Because MSSQL and
 * REST share the same {@link #effectiveFrom cursor}, their incremental behaviour cannot diverge.
 */
@Service
public class StagingIngestionService {

    /** Path of the consolidated values endpoint when a REST source does not override it. */
    private static final String DEFAULT_REST_VALUES_PATH = "/api/data/values";

    private final MeasureRepository measureRepository;
    private final StagingValueRepository stagingRepository;
    private final DataApiClient dataApiClient;
    private final JdbcDataSourceRegistry dataSourceRegistry;

    public StagingIngestionService(MeasureRepository measureRepository,
                                   StagingValueRepository stagingRepository,
                                   DataApiClient dataApiClient,
                                   JdbcDataSourceRegistry dataSourceRegistry) {
        this.measureRepository = measureRepository;
        this.stagingRepository = stagingRepository;
        this.dataApiClient = dataApiClient;
        this.dataSourceRegistry = dataSourceRegistry;
    }

    /**
     * The result of an ingest: how many staging rows landed, plus — for REST sources only — when the
     * source last reported its data changed. MSSQL carries {@code null} here (change-detection is
     * deferred, so MSSQL freshness stays TTL-bounded); REST echoes {@link DataSeriesResponse#sourceChangedAt()}.
     * {@link IngestionOrchestrationService} stamps a non-null {@code sourceChangedAt} onto the same
     * control row it updates on a successful ingest, so a later gate decision can re-pull when the
     * source reports a newer change.
     */
    public record IngestOutcome(int rowsStaged, Instant sourceChangedAt) { }

    /** What a source read yielded: the staging rows plus the source's optional change timestamp. */
    private record SourceRead(List<StagingValue> rows, Instant sourceChangedAt) { }

    /**
     * Copy the {@code entityKey}'s data for one table into staging as of {@code asOf} (point-in-time).
     * Both source kinds land just the latest observation {@code <= asOf} (for MSSQL, the latest row —
     * or latest row per KEY_VALUE code; for REST, the single snapshot), which is what a term with no
     * window needs (the resolver picks the newest staged value {@code <= asOf}).
     *
     * @return the ingest outcome: rows written (0 when the table has no measures / no data) and the
     *         source's optional change timestamp (REST only; {@code null} for MSSQL).
     */
    @Transactional
    public IngestOutcome ingest(DataSourceTable table, String entityKey, LocalDate asOf) {
        return ingest(table, entityKey, asOf, asOf);
    }

    /**
     * US-DS-12: copy the {@code entityKey}'s data for one table into staging covering the inclusive
     * range {@code [from, asOf]}. When {@code from} is strictly before {@code asOf} a windowed term
     * needs history, so ingestion pulls a <em>series</em> — only the dates newer than what is already
     * staged (the full window on the first run, or a reach-back refetch when {@code from} predates
     * everything staged). When {@code from == asOf} (a point-in-time term) it lands only the latest
     * observation {@code <= asOf}. Both MSSQL and REST honour {@code from} identically via the shared
     * {@link #effectiveFrom cursor}.
     *
     * @return the ingest outcome: rows written (0 when the table has no measures / no data) and the
     *         source's optional change timestamp (REST only; {@code null} for MSSQL).
     */
    @Transactional
    public IngestOutcome ingest(DataSourceTable table, String entityKey, LocalDate from, LocalDate asOf) {
        List<Measure> measures = measureRepository.findByDataSourceTableId(table.getId());
        if (measures.isEmpty()) {
            return new IngestOutcome(0, null);
        }
        DataSource source = table.getDataSource();
        SourceRead read = switch (source.getType()) {
            case MSSQL -> readFromMssql(source, table, measures, entityKey, from, asOf);
            case REST -> readFromRest(source, table, measures, entityKey, from, asOf);
        };
        List<StagingValue> rows = read.rows();
        if (rows.isEmpty()) {
            return new IngestOutcome(0, read.sourceChangedAt());
        }

        Set<LocalDate> dates = rows.stream().map(StagingValue::getAsOfDate).collect(Collectors.toSet());
        stagingRepository.deleteByDataSourceTableIdAndEntityKeyAndAsOfDateIn(table.getId(), entityKey, dates);

        String batchId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        for (StagingValue row : rows) {
            row.setIngestBatchId(batchId);
            row.setIngestedAt(now);
        }
        // Flush the inserts to the shared transaction connection immediately. AssessmentService runs
        // ingest + resolve in one @Transactional; the resolver reads staging via a raw JdbcTemplate,
        // which does NOT trigger a Hibernate auto-flush — so without this a first-run assessment on a
        // fresh entity would not see the rows it just ingested. Flushed (still uncommitted) rows are
        // visible to the JdbcTemplate because JpaTransactionManager binds the same JDBC connection.
        stagingRepository.saveAllAndFlush(rows);
        return new IngestOutcome(rows.size(), read.sourceChangedAt());
    }

    /** The identifier a measure is stored under in staging: {@code sourceColumn} / {@code sourceCode}. */
    private static String itemCodeOf(Measure measure, boolean keyValue) {
        return keyValue ? measure.getSourceCode() : measure.getSourceColumn();
    }

    /**
     * The lower bound to actually re-read for a windowed pull, given what is already staged — shared by
     * the MSSQL and REST paths so their incremental behaviour cannot diverge. Three cases:
     * <ol>
     *   <li><b>reach-back</b> — {@code from} is earlier than the earliest staged as-of: return
     *       {@code from} for one full {@code [from, asOf]} refetch (the delete-then-insert overwrites
     *       the window, filling older dates a widened look-back now needs);</li>
     *   <li><b>forward-incremental</b> — a staged max at or after {@code from}: return
     *       {@code max + 1 day} so only dates newer than the watermark are pulled;</li>
     *   <li><b>first run</b> — nothing staged yet: return {@code from} (the full window).</li>
     * </ol>
     */
    private LocalDate effectiveFrom(String tableId, String entityKey, LocalDate from) {
        Optional<LocalDate> min = stagingRepository.findMinAsOfDate(tableId, entityKey);
        if (min.isPresent() && from.isBefore(min.get())) {
            return from; // reach-back: refetch the full window
        }
        return stagingRepository.findMaxAsOfDate(tableId, entityKey)
                .filter(max -> !max.isBefore(from))
                .map(max -> max.plusDays(1))
                .orElse(from);
    }

    /**
     * MSSQL read. A windowed term ({@code from} strictly before {@code asOf}) copies the incremental
     * range {@code [effectiveFrom, asOf]}; a point-in-time term ({@code from == asOf} / {@code null})
     * copies only the latest row(s) {@code <= asOf}. The chosen lower bound is passed to the layout
     * readers as {@code lowerBound} — non-null selects the range query, {@code null} the latest query.
     *
     * <p>MSSQL change-detection is deferred, so the read carries no {@code sourceChangedAt} ({@code null}
     * in the returned {@link SourceRead}); MSSQL freshness stays TTL-bounded — unlike {@link #readFromRest},
     * which echoes the source's change timestamp.
     */
    private SourceRead readFromMssql(DataSource source, DataSourceTable table,
                                     List<Measure> measures, String entityKey,
                                     LocalDate from, LocalDate asOf) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSourceRegistry.dataSourceFor(source));
        String schema = SqlIdentifiers.optional(table.getSchemaName(), "schema");
        String tableName = SqlIdentifiers.require(table.getTableName(), "tableName");
        String keyColumn = SqlIdentifiers.require(table.getEntityKeyColumn(), "entityKeyColumn");
        String asOfColumn = SqlIdentifiers.require(table.getAsOfColumn(), "asOfColumn");
        String qualified = schema == null ? tableName : schema + "." + tableName;
        boolean keyValue = table.getLayout() == TableLayout.KEY_VALUE;

        LocalDate lowerBound = null; // null ⇒ point-in-time (latest <= asOf)
        if (from != null && from.isBefore(asOf)) {
            lowerBound = effectiveFrom(table.getId(), entityKey, from);
            if (lowerBound.isAfter(asOf)) {
                return new SourceRead(List.of(), null); // watermark at/after asOf → nothing newer to copy
            }
        }
        List<StagingValue> rows = keyValue
                ? readKeyValueFromMssql(jdbc, table, measures, entityKey, lowerBound, asOf,
                        qualified, keyColumn, asOfColumn)
                : readColumnarFromMssql(jdbc, table, measures, entityKey, lowerBound, asOf,
                        qualified, keyColumn, asOfColumn);
        return new SourceRead(rows, null); // MSSQL: no source-changed signal (deferred)
    }

    @SuppressFBWarnings(
            value = "SQL_INJECTION_SPRING_JDBC",
            justification = "Every table/schema/column identifier is validated against SqlIdentifiers' "
                    + "strict pattern by the caller before concatenation; the only data values (entity "
                    + "key and the as-of lower/upper bounds) are bound as '?' parameters. FindSecBugs "
                    + "cannot prove the dynamic SQL is safe; the validation above guarantees it.")
    private List<StagingValue> readColumnarFromMssql(JdbcTemplate jdbc, DataSourceTable table,
                                                     List<Measure> measures, String entityKey,
                                                     LocalDate from, LocalDate asOf,
                                                     String qualified, String keyColumn, String asOfColumn) {
        // Distinct physical columns to copy, each with the declared type of its measure.
        Map<String, DataColumnType> typeByColumn = new LinkedHashMap<>();
        for (Measure m : measures) {
            if (m.getSourceColumn() != null) {
                typeByColumn.put(SqlIdentifiers.require(m.getSourceColumn(), "sourceColumn"), m.getDataType());
            }
        }
        if (typeByColumn.isEmpty()) {
            return List.of();
        }
        String select = String.join(", ", typeByColumn.keySet());
        String sql;
        Object[] args;
        if (from != null) {
            // Windowed: the incremental range [from, asOf].
            sql = "SELECT " + asOfColumn + " AS aod, " + select + " FROM " + qualified
                    + " WHERE " + keyColumn + " = ? AND " + asOfColumn + " >= ? AND " + asOfColumn + " <= ?";
            args = new Object[]{entityKey, java.sql.Date.valueOf(from), java.sql.Date.valueOf(asOf)};
        } else {
            // Point-in-time: the single latest row <= asOf (LATEST/CURRENT semantics).
            sql = "SELECT TOP 1 " + asOfColumn + " AS aod, " + select + " FROM " + qualified
                    + " WHERE " + keyColumn + " = ? AND " + asOfColumn + " <= ?"
                    + " ORDER BY " + asOfColumn + " DESC";
            args = new Object[]{entityKey, java.sql.Date.valueOf(asOf)};
        }

        List<List<StagingValue>> perRow = jdbc.query(sql, args, (rs, rowNum) -> {
            LocalDate rowDate = toLocalDate(rs.getObject("aod"));
            List<StagingValue> cells = new ArrayList<>();
            for (Map.Entry<String, DataColumnType> col : typeByColumn.entrySet()) {
                Object raw = rs.getObject(col.getKey());
                if (raw != null) {
                    cells.add(build(table, entityKey, rowDate, col.getKey(), col.getValue(), raw));
                }
            }
            return cells;
        });
        List<StagingValue> out = new ArrayList<>();
        for (List<StagingValue> cells : perRow) {
            out.addAll(cells);
        }
        return out;
    }

    @SuppressFBWarnings(
            value = "SQL_INJECTION_SPRING_JDBC",
            justification = "Every table/schema/column identifier (including the KEY_VALUE code/value "
                    + "columns) is validated against SqlIdentifiers' strict pattern before "
                    + "concatenation; the only data values (entity key, the as-of lower/upper bounds "
                    + "and the KEY_VALUE measure codes) are bound as '?' parameters. FindSecBugs cannot "
                    + "prove the dynamic SQL is safe; the validation above guarantees it.")
    private List<StagingValue> readKeyValueFromMssql(JdbcTemplate jdbc, DataSourceTable table,
                                                     List<Measure> measures, String entityKey,
                                                     LocalDate from, LocalDate asOf,
                                                     String qualified, String keyColumn, String asOfColumn) {
        String codeColumn = SqlIdentifiers.require(table.getCodeColumn(), "codeColumn");
        String valueColumn = SqlIdentifiers.require(table.getValueColumn(), "valueColumn");
        Map<String, DataColumnType> typeByCode = new LinkedHashMap<>();
        for (Measure m : measures) {
            if (m.getSourceCode() != null) {
                typeByCode.put(m.getSourceCode(), m.getDataType());
            }
        }
        if (typeByCode.isEmpty()) {
            return List.of();
        }
        String placeholders = typeByCode.keySet().stream().map(k -> "?").collect(Collectors.joining(", "));
        List<Object> args = new ArrayList<>();
        args.add(entityKey);
        String sql;
        if (from != null) {
            // Windowed: the incremental range [from, asOf].
            args.add(java.sql.Date.valueOf(from));
            args.add(java.sql.Date.valueOf(asOf));
            args.addAll(typeByCode.keySet());
            sql = "SELECT " + asOfColumn + " AS aod, " + codeColumn + " AS code, " + valueColumn + " AS val"
                    + " FROM " + qualified
                    + " WHERE " + keyColumn + " = ? AND " + asOfColumn + " >= ? AND " + asOfColumn + " <= ? AND "
                    + codeColumn + " IN (" + placeholders + ")";
        } else {
            // Point-in-time: the latest row per code <= asOf (LATEST/CURRENT semantics, per code).
            args.add(java.sql.Date.valueOf(asOf));
            args.addAll(typeByCode.keySet());
            sql = "SELECT aod, code, val FROM ("
                    + "SELECT " + asOfColumn + " AS aod, " + codeColumn + " AS code, " + valueColumn + " AS val, "
                    + "ROW_NUMBER() OVER (PARTITION BY " + codeColumn + " ORDER BY " + asOfColumn + " DESC) AS rn"
                    + " FROM " + qualified
                    + " WHERE " + keyColumn + " = ? AND " + asOfColumn + " <= ? AND "
                    + codeColumn + " IN (" + placeholders + ")) sub WHERE rn = 1";
        }

        return jdbc.query(sql, args.toArray(), (rs, rowNum) -> {
            LocalDate rowDate = toLocalDate(rs.getObject("aod"));
            String code = rs.getString("code");
            DataColumnType type = typeByCode.getOrDefault(code, DataColumnType.NUMBER);
            return build(table, entityKey, rowDate, code, type, rs.getObject("val"));
        });
    }

    /**
     * REST read via the one as-of-aware endpoint (WS3), routed to the source's own {@code baseUrl}/
     * {@code valuesPath} (WS4). A windowed term ({@code from} strictly before {@code asOf}) requests a
     * series over the incremental range {@code [effectiveFrom, asOf]} — only the dates newer than the
     * staged watermark (the full window on the first run, or a reach-back refetch when a widened
     * look-back needs older dates). A point-in-time term ({@code from == asOf} / {@code null}) sends a
     * {@code null} {@code from} so the source returns just the single latest point {@code <= asOf},
     * mirroring the MSSQL latest-row read.
     *
     * <p>Unlike MSSQL, the read carries the source's {@link DataSeriesResponse#sourceChangedAt()} back to
     * the caller (may be {@code null} if the source omits it), so a coherent source-change timestamp can
     * be stamped on the same control row the ingest updates.
     */
    private SourceRead readFromRest(DataSource source, DataSourceTable table,
                                    List<Measure> measures, String entityKey,
                                    LocalDate from, LocalDate asOf) {
        boolean keyValue = table.getLayout() == TableLayout.KEY_VALUE;
        Map<String, DataColumnType> typeByKey = new LinkedHashMap<>();
        for (Measure m : measures) {
            String key = itemCodeOf(m, keyValue);
            if (key != null) {
                typeByKey.put(key, m.getDataType());
            }
        }
        if (typeByKey.isEmpty()) {
            return new SourceRead(List.of(), null); // no source call issued → no change signal
        }
        List<String> keys = new ArrayList<>(typeByKey.keySet());

        // null ⇒ point-in-time (single latest point <= asOf); non-null ⇒ windowed incremental cursor
        // (shared with MSSQL): fetch only dates newer than what is staged, or the full window on a
        // first run / reach-back.
        LocalDate windowFrom = null;
        if (from != null && from.isBefore(asOf)) {
            windowFrom = effectiveFrom(table.getId(), entityKey, from);
            if (windowFrom.isAfter(asOf)) {
                return new SourceRead(List.of(), null); // watermark at/after asOf → no source call issued
            }
        }

        RestEndpoint endpoint = new RestEndpoint(
                source.configOrDefault("baseUrl", null),
                source.configOrDefault("valuesPath", DEFAULT_REST_VALUES_PATH),
                source.configOrDefault("auth", null));
        DataSeriesResponse response = dataApiClient.fetchValues(endpoint, entityKey, windowFrom, asOf, keys);

        boolean pointInTime = windowFrom == null;
        List<StagingValue> out = new ArrayList<>();
        for (DataPoint point : response.safePoints()) {
            LocalDate rowDate = point.asOfDate();
            if (rowDate == null) {
                if (!pointInTime) {
                    continue; // a windowed point with no date can't be placed on the timeline
                }
                rowDate = asOf; // a point-in-time snapshot with no date lands at the requested ceiling
            }
            for (Map.Entry<String, Object> entry : point.safeValues().entrySet()) {
                DataColumnType type = typeByKey.get(entry.getKey());
                if (type != null && entry.getValue() != null) {
                    out.add(build(table, entityKey, rowDate, entry.getKey(), type, entry.getValue()));
                }
            }
        }
        return new SourceRead(out, response.sourceChangedAt());
    }

    private static StagingValue build(DataSourceTable table, String entityKey, LocalDate asOf,
                                      String itemCode, DataColumnType type, Object raw) {
        StagingValue value = new StagingValue();
        value.setDataSourceTable(table);
        value.setEntityKey(entityKey);
        value.setAsOfDate(asOf);
        value.setItemCode(itemCode);
        value.setDataType(type);
        applyTypedValue(value, type, raw);
        return value;
    }

    /** Route a raw JDBC/JSON value into the typed staging column selected by {@code type}. */
    private static void applyTypedValue(StagingValue value, DataColumnType type, Object raw) {
        if (raw == null) {
            return;
        }
        switch (type) {
            case NUMBER -> value.setNumValue(toBigDecimal(raw));
            case DATE -> value.setDateValue(toLocalDate(raw));
            case BOOLEAN -> value.setBoolValue(toBoolean(raw));
            case STRING -> value.setStrValue(raw.toString());
        }
    }

    private static BigDecimal toBigDecimal(Object raw) {
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (raw instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (raw instanceof Boolean flag) {
            return flag ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        String text = raw.toString().trim();
        return text.isEmpty() ? null : new BigDecimal(text);
    }

    private static LocalDate toLocalDate(Object raw) {
        if (raw instanceof LocalDate date) {
            return date;
        }
        if (raw instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (raw instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        if (raw instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        return LocalDate.parse(raw.toString());
    }

    private static Boolean toBoolean(Object raw) {
        if (raw instanceof Boolean flag) {
            return flag;
        }
        if (raw instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(raw.toString().trim());
    }
}
