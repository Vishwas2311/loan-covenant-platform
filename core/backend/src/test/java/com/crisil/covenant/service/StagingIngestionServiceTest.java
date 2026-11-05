package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.config.IngestionProperties;
import com.crisil.covenant.domain.DataPoint;
import com.crisil.covenant.domain.DataSeriesResponse;
import com.crisil.covenant.domain.RestEndpoint;
import com.crisil.covenant.entity.DataSource;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.entity.Measure;
import com.crisil.covenant.entity.StagingValue;
import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.enums.DataSourceType;
import com.crisil.covenant.enums.TableLayout;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.exception.SourceReadException;
import com.crisil.covenant.repository.MeasureRepository;
import com.crisil.covenant.repository.StagingValueRepository;
import com.crisil.covenant.util.JdbcDataSourceRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Verifies that ingestion lands the correct typed rows into staging for both source kinds. The
 * MSSQL read path runs against a real in-memory H2 database (MODE=MSSQLServer) standing in for the
 * live source; the persistence side ({@link StagingValueRepository}) is mocked so the captured rows
 * can be asserted without a JPA context. The REST path uses a mocked {@link DataApiClient}.
 */
@ExtendWith(MockitoExtension.class)
class StagingIngestionServiceTest {

    private static final String LIVE_URL =
            "jdbc:h2:mem:stagingsrc;MODE=MSSQLServer;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 19);

    @Mock private MeasureRepository measureRepository;
    @Mock private StagingValueRepository stagingRepository;
    @Mock private DataApiClient dataApiClient;
    @Captor private ArgumentCaptor<List<StagingValue>> savedCaptor;

    private StagingIngestionService service;
    private JdbcDataSourceRegistry dataSourceRegistry;

    @BeforeAll
    static void seedLive() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(LIVE_URL));
        // COLUMNAR live table: one column per measure, full history. 'bonus' is always NULL so the
        // resolver's null-cell skip branch can be exercised.
        jdbc.execute("DROP TABLE IF EXISTS cp_fin");
        jdbc.execute("CREATE TABLE cp_fin (cp_id VARCHAR(32), as_of_date DATE, "
                + "pat DECIMAL(12,2), rating VARCHAR(8), bonus DECIMAL(12,2))");
        jdbc.update("INSERT INTO cp_fin VALUES ('CP-1','2025-01-01',20,'B',NULL)");
        jdbc.update("INSERT INTO cp_fin VALUES ('CP-1','2026-01-01',30,'C',NULL)");
        jdbc.update("INSERT INTO cp_fin VALUES ('CP-1','2027-01-01',40,'D',NULL)"); // after as-of -> excluded
        jdbc.update("INSERT INTO cp_fin VALUES ('CP-2','2026-01-01',999,'Z',NULL)"); // other entity -> excluded

        // KEY_VALUE live table: many measures share one value column keyed by ratio_code.
        jdbc.execute("DROP TABLE IF EXISTS cp_ratios");
        jdbc.execute("CREATE TABLE cp_ratios (cp_id VARCHAR(32), as_of_date DATE, "
                + "ratio_code VARCHAR(16), ratio_value DECIMAL(12,2))");
        // Two dates per registered code so point-in-time picks the latest per code and a windowed pull
        // sees the range; the 2025 rows are superseded by the 2026 rows for a LATEST/CURRENT read.
        jdbc.update("INSERT INTO cp_ratios VALUES ('CP-1','2025-01-01','DSCR',1.0)");
        jdbc.update("INSERT INTO cp_ratios VALUES ('CP-1','2025-01-01','ICR',2.0)");
        jdbc.update("INSERT INTO cp_ratios VALUES ('CP-1','2026-01-01','DSCR',1.5)");
        jdbc.update("INSERT INTO cp_ratios VALUES ('CP-1','2026-01-01','ICR',2.5)");
        jdbc.update("INSERT INTO cp_ratios VALUES ('CP-1','2026-01-01','XX',9.9)"); // unregistered code -> excluded
        jdbc.update("INSERT INTO cp_ratios VALUES ('CP-1','2026-01-01','NULLR',NULL)"); // null value -> typed columns stay null

        // Schema-qualified live table: exercises the schema.tableName branch of the reader.
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS fin");
        jdbc.execute("DROP TABLE IF EXISTS fin.cp_scoped");
        jdbc.execute("CREATE TABLE fin.cp_scoped (cp_id VARCHAR(32), as_of_date DATE, pat DECIMAL(12,2))");
        jdbc.update("INSERT INTO fin.cp_scoped VALUES ('CP-1','2026-01-01',77)");
    }

    @BeforeEach
    void setUp() {
        // A real registry so the MSSQL path exercises the pooled Hikari DataSource over the H2 source.
        dataSourceRegistry = new JdbcDataSourceRegistry(new IngestionProperties());
        service = new StagingIngestionService(measureRepository, stagingRepository, dataApiClient,
                dataSourceRegistry);
    }

    @AfterEach
    void closePools() {
        dataSourceRegistry.closeAll();
    }

    @Test
    void mssqlColumnarWindowedCopiesTypedCellsInRange() {
        DataSourceTable table = mssqlTable("cp_fin", TableLayout.COLUMNAR);
        Measure pat = columnMeasure("pat", DataColumnType.NUMBER);
        Measure rating = columnMeasure("rating", DataColumnType.STRING);
        when(measureRepository.findByDataSourceTableId("tbl")).thenReturn(List.of(pat, rating));
        LocalDate from = LocalDate.of(2024, 6, 19);
        // First run (nothing staged): the full window [from, asOf] is copied.
        when(stagingRepository.findMinAsOfDate("tbl", "CP-1")).thenReturn(Optional.empty());
        when(stagingRepository.findMaxAsOfDate("tbl", "CP-1")).thenReturn(Optional.empty());

        int written = service.ingest(table, "CP-1", from, AS_OF).rowsStaged();

        // 2 in-range dates (2025, 2026) x 2 columns = 4 cells; 2027 and CP-2 excluded.
        assertThat(written).isEqualTo(4);
        InOrder inOrder = Mockito.inOrder(stagingRepository);
        // Idempotency: delete the dates being re-read first, then insert.
        inOrder.verify(stagingRepository).deleteByDataSourceTableIdAndEntityKeyAndAsOfDateIn(
                eq("tbl"), eq("CP-1"),
                argThat(dates -> dates.size() == 2
                        && dates.containsAll(List.of(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1)))));
        inOrder.verify(stagingRepository).saveAllAndFlush(savedCaptor.capture());

        List<StagingValue> rows = savedCaptor.getValue();
        assertThat(rows).hasSize(4);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getEntityKey()).isEqualTo("CP-1");
            assertThat(r.getDataSourceTable()).isSameAs(table);
            assertThat(r.getIngestBatchId()).isNotBlank();
            assertThat(r.getIngestedAt()).isNotNull();
        });
        // Both in-range pat cells land (20 @2025, 30 @2026); the latest rating cell is "C" (STRING).
        assertThat(numFor(rows, "pat", LocalDate.of(2026, 1, 1))).isEqualTo(30.0);
        assertThat(numFor(rows, "pat", LocalDate.of(2025, 1, 1))).isEqualTo(20.0);
        StagingValue latestRating = rowFor(rows, "rating", LocalDate.of(2026, 1, 1));
        assertThat(latestRating.getDataType()).isEqualTo(DataColumnType.STRING);
        assertThat(latestRating.getStrValue()).isEqualTo("C");
        assertThat(latestRating.getNumValue()).isNull();
    }

    @Test
    void mssqlColumnarPointInTimeCopiesOnlyLatestRow() {
        DataSourceTable table = mssqlTable("cp_fin", TableLayout.COLUMNAR);
        Measure pat = columnMeasure("pat", DataColumnType.NUMBER);
        Measure rating = columnMeasure("rating", DataColumnType.STRING);
        when(measureRepository.findByDataSourceTableId("tbl")).thenReturn(List.of(pat, rating));

        // Single-arg (from == asOf) is a point-in-time term: only the latest row <= asOf is copied,
        // so effectiveFrom (and thus the staging watermark) is never consulted.
        int written = service.ingest(table, "CP-1", AS_OF).rowsStaged();

        assertThat(written).isEqualTo(2); // pat + rating, both @2026-01-01 (the latest row <= asOf)
        verify(stagingRepository).deleteByDataSourceTableIdAndEntityKeyAndAsOfDateIn(
                eq("tbl"), eq("CP-1"),
                argThat(dates -> dates.size() == 1 && dates.contains(LocalDate.of(2026, 1, 1))));
        verify(stagingRepository).saveAllAndFlush(savedCaptor.capture());
        List<StagingValue> rows = savedCaptor.getValue();
        assertThat(rows).hasSize(2);
        assertThat(numFor(rows, "pat", LocalDate.of(2026, 1, 1))).isEqualTo(30.0);
        assertThat(rowFor(rows, "rating", LocalDate.of(2026, 1, 1)).getStrValue()).isEqualTo("C");
    }

    @Test
    void mssqlColumnarIncrementalCopiesOnlyDatesNewerThanWatermark() {
        DataSourceTable table = mssqlTable("cp_fin", TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl"))
                .thenReturn(List.of(columnMeasure("pat", DataColumnType.NUMBER)));
        LocalDate from = LocalDate.of(2024, 6, 19);
        // A prior run already staged [from .. 2025-01-01]; only dates strictly after the watermark
        // are re-read, so 2025-01-01 is skipped and only 2026-01-01 lands.
        when(stagingRepository.findMinAsOfDate("tbl", "CP-1")).thenReturn(Optional.of(from));
        when(stagingRepository.findMaxAsOfDate("tbl", "CP-1"))
                .thenReturn(Optional.of(LocalDate.of(2025, 1, 1)));

        int written = service.ingest(table, "CP-1", from, AS_OF).rowsStaged();

        assertThat(written).isEqualTo(1);
        verify(stagingRepository).deleteByDataSourceTableIdAndEntityKeyAndAsOfDateIn(
                eq("tbl"), eq("CP-1"),
                argThat(dates -> dates.size() == 1 && dates.contains(LocalDate.of(2026, 1, 1))));
        verify(stagingRepository).saveAllAndFlush(savedCaptor.capture());
        assertThat(numFor(savedCaptor.getValue(), "pat", LocalDate.of(2026, 1, 1))).isEqualTo(30.0);
    }

    @Test
    void mssqlColumnarReachBackRefetchesFullWindowWhenFromPredatesStaged() {
        DataSourceTable table = mssqlTable("cp_fin", TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl"))
                .thenReturn(List.of(columnMeasure("pat", DataColumnType.NUMBER)));
        LocalDate from = LocalDate.of(2024, 1, 1);
        // The requested window starts before the earliest staged as-of (2026-01-01), so the guard
        // does one full [from, asOf] refetch rather than a forward-only incremental pull; the staged
        // max is never consulted on this branch.
        when(stagingRepository.findMinAsOfDate("tbl", "CP-1"))
                .thenReturn(Optional.of(LocalDate.of(2026, 1, 1)));

        int written = service.ingest(table, "CP-1", from, AS_OF).rowsStaged();

        // Both in-range dates are re-read (2025 + 2026), including the older date the widened look-back needs.
        assertThat(written).isEqualTo(2);
        verify(stagingRepository).deleteByDataSourceTableIdAndEntityKeyAndAsOfDateIn(
                eq("tbl"), eq("CP-1"),
                argThat(dates -> dates.size() == 2
                        && dates.containsAll(List.of(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1)))));
        verify(stagingRepository).saveAllAndFlush(savedCaptor.capture());
        List<StagingValue> rows = savedCaptor.getValue();
        assertThat(numFor(rows, "pat", LocalDate.of(2025, 1, 1))).isEqualTo(20.0);
        assertThat(numFor(rows, "pat", LocalDate.of(2026, 1, 1))).isEqualTo(30.0);
    }

    @Test
    void mssqlKeyValuePointInTimeCopiesLatestPerRegisteredCode() {
        DataSourceTable table = mssqlTable("cp_ratios", TableLayout.KEY_VALUE);
        table.setCodeColumn("ratio_code");
        table.setValueColumn("ratio_value");
        Measure dscr = codeMeasure("DSCR");
        Measure icr = codeMeasure("ICR");
        when(measureRepository.findByDataSourceTableId("tbl")).thenReturn(List.of(dscr, icr));

        // Point-in-time: the latest row per registered code <= asOf. DSCR/ICR each have a 2025 and a
        // 2026 row, so only the 2026 values land; the unregistered 'XX' row is filtered by the IN-list.
        int written = service.ingest(table, "CP-1", AS_OF).rowsStaged();

        assertThat(written).isEqualTo(2);
        verify(stagingRepository).saveAllAndFlush(savedCaptor.capture());
        List<StagingValue> rows = savedCaptor.getValue();
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> assertThat(r.getAsOfDate()).isEqualTo(LocalDate.of(2026, 1, 1)));
        assertThat(numFor(rows, "DSCR", LocalDate.of(2026, 1, 1))).isEqualTo(1.5);
        assertThat(numFor(rows, "ICR", LocalDate.of(2026, 1, 1))).isEqualTo(2.5);
        assertThat(rows).noneMatch(r -> "XX".equals(r.getItemCode()));
    }

    @Test
    void mssqlKeyValueWindowedCopiesEveryDateInRange() {
        DataSourceTable table = mssqlTable("cp_ratios", TableLayout.KEY_VALUE);
        table.setCodeColumn("ratio_code");
        table.setValueColumn("ratio_value");
        Measure dscr = codeMeasure("DSCR");
        Measure icr = codeMeasure("ICR");
        when(measureRepository.findByDataSourceTableId("tbl")).thenReturn(List.of(dscr, icr));
        LocalDate from = LocalDate.of(2024, 6, 19);
        // First run: the full window is copied, so every dated observation of each registered code
        // lands (both the 2025 and 2026 rows) — not just the latest per code.
        when(stagingRepository.findMinAsOfDate("tbl", "CP-1")).thenReturn(Optional.empty());
        when(stagingRepository.findMaxAsOfDate("tbl", "CP-1")).thenReturn(Optional.empty());

        int written = service.ingest(table, "CP-1", from, AS_OF).rowsStaged();

        assertThat(written).isEqualTo(4);
        verify(stagingRepository).saveAllAndFlush(savedCaptor.capture());
        List<StagingValue> rows = savedCaptor.getValue();
        assertThat(numFor(rows, "DSCR", LocalDate.of(2025, 1, 1))).isEqualTo(1.0);
        assertThat(numFor(rows, "DSCR", LocalDate.of(2026, 1, 1))).isEqualTo(1.5);
        assertThat(numFor(rows, "ICR", LocalDate.of(2025, 1, 1))).isEqualTo(2.0);
        assertThat(numFor(rows, "ICR", LocalDate.of(2026, 1, 1))).isEqualTo(2.5);
        assertThat(rows).noneMatch(r -> "XX".equals(r.getItemCode()));
    }

    @Test
    void restLandsSnapshotUnderResponseAsOfDate() {
        DataSourceTable table = restTable(TableLayout.COLUMNAR);
        Measure pat = columnMeasure("pat", DataColumnType.NUMBER);
        Measure rating = columnMeasure("rating", DataColumnType.STRING);
        when(measureRepository.findByDataSourceTableId("tbl")).thenReturn(List.of(pat, rating));
        LocalDate snapshotDate = LocalDate.of(2026, 3, 31);
        // Point-in-time (from == null): the source returns a single latest point at its own date.
        when(dataApiClient.fetchValues(any(), eq("CP-1"), isNull(), eq(AS_OF), any()))
                .thenReturn(new DataSeriesResponse("CP-1",
                        List.of(new DataPoint(snapshotDate, Map.of("pat", 30, "rating", "C"))),
                        List.of(), null));

        int written = service.ingest(table, "CP-1", AS_OF).rowsStaged();

        assertThat(written).isEqualTo(2);
        // The landed rows carry the point's own as-of date, and only that date is cleared first.
        verify(stagingRepository).deleteByDataSourceTableIdAndEntityKeyAndAsOfDateIn(
                eq("tbl"), eq("CP-1"),
                argThat(dates -> dates.size() == 1 && dates.contains(snapshotDate)));
        verify(stagingRepository).saveAllAndFlush(savedCaptor.capture());
        List<StagingValue> rows = savedCaptor.getValue();
        assertThat(rows).allSatisfy(r -> assertThat(r.getAsOfDate()).isEqualTo(snapshotDate));
        assertThat(numFor(rows, "pat", snapshotDate)).isEqualTo(30.0);
        assertThat(rowFor(rows, "rating", snapshotDate).getStrValue()).isEqualTo("C");
    }

    @Test
    void restFallsBackToRequestedAsOfWhenResponseHasNone() {
        DataSourceTable table = restTable(TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl"))
                .thenReturn(List.of(columnMeasure("pat", DataColumnType.NUMBER)));
        // Point-in-time (from == null): a snapshot point with no date lands at the requested ceiling.
        when(dataApiClient.fetchValues(any(), eq("CP-1"), isNull(), eq(AS_OF), any()))
                .thenReturn(new DataSeriesResponse("CP-1",
                        List.of(new DataPoint(null, Map.of("pat", 12))), List.of(), null));

        service.ingest(table, "CP-1", AS_OF);

        verify(stagingRepository).saveAllAndFlush(savedCaptor.capture());
        assertThat(savedCaptor.getValue()).singleElement()
                .satisfies(r -> assertThat(r.getAsOfDate()).isEqualTo(AS_OF));
    }

    @Test
    void restSeriesLandsEveryDatedPointOnFirstRun() {
        DataSourceTable table = restTable(TableLayout.COLUMNAR);
        Measure pat = columnMeasure("pat", DataColumnType.NUMBER);
        when(measureRepository.findByDataSourceTableId("tbl")).thenReturn(List.of(pat));
        LocalDate from = LocalDate.of(2024, 6, 19);
        // First run: nothing staged yet, so the full window [from, asOf] is fetched as a series.
        when(stagingRepository.findMinAsOfDate("tbl", "CP-1")).thenReturn(Optional.empty());
        when(stagingRepository.findMaxAsOfDate("tbl", "CP-1")).thenReturn(Optional.empty());
        when(dataApiClient.fetchValues(any(), eq("CP-1"), eq(from), eq(AS_OF), any()))
                .thenReturn(new DataSeriesResponse("CP-1", List.of(
                        new DataPoint(LocalDate.of(2024, 12, 31), Map.of("pat", 10)),
                        new DataPoint(LocalDate.of(2025, 12, 31), Map.of("pat", 20)),
                        new DataPoint(LocalDate.of(2026, 3, 31), Map.of("pat", 30))), List.of(), null));

        int written = service.ingest(table, "CP-1", from, AS_OF).rowsStaged();

        assertThat(written).isEqualTo(3);
        verify(dataApiClient).fetchValues(any(), eq("CP-1"), eq(from), eq(AS_OF), any());
        verify(stagingRepository).deleteByDataSourceTableIdAndEntityKeyAndAsOfDateIn(
                eq("tbl"), eq("CP-1"),
                argThat(dates -> dates.size() == 3
                        && dates.containsAll(List.of(LocalDate.of(2024, 12, 31),
                                LocalDate.of(2025, 12, 31), LocalDate.of(2026, 3, 31)))));
        verify(stagingRepository).saveAllAndFlush(savedCaptor.capture());
        List<StagingValue> rows = savedCaptor.getValue();
        assertThat(numFor(rows, "pat", LocalDate.of(2024, 12, 31))).isEqualTo(10.0);
        assertThat(numFor(rows, "pat", LocalDate.of(2025, 12, 31))).isEqualTo(20.0);
        assertThat(numFor(rows, "pat", LocalDate.of(2026, 3, 31))).isEqualTo(30.0);
    }

    @Test
    void restSeriesFetchesOnlyDatesNewerThanWatermark() {
        DataSourceTable table = restTable(TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl"))
                .thenReturn(List.of(columnMeasure("pat", DataColumnType.NUMBER)));
        LocalDate from = LocalDate.of(2024, 6, 19);
        LocalDate watermark = LocalDate.of(2025, 12, 31);
        // A prior run already staged up to the watermark, so only dates strictly after it are fetched.
        when(stagingRepository.findMaxAsOfDate("tbl", "CP-1")).thenReturn(Optional.of(watermark));
        when(dataApiClient.fetchValues(any(), eq("CP-1"), eq(watermark.plusDays(1)), eq(AS_OF), any()))
                .thenReturn(new DataSeriesResponse("CP-1", List.of(
                        new DataPoint(LocalDate.of(2026, 3, 31), Map.of("pat", 30))), List.of(), null));

        int written = service.ingest(table, "CP-1", from, AS_OF).rowsStaged();

        assertThat(written).isEqualTo(1);
        verify(dataApiClient).fetchValues(any(), eq("CP-1"), eq(watermark.plusDays(1)), eq(AS_OF), any());
        verify(stagingRepository).saveAllAndFlush(savedCaptor.capture());
        assertThat(numFor(savedCaptor.getValue(), "pat", LocalDate.of(2026, 3, 31))).isEqualTo(30.0);
    }

    @Test
    void restSeriesIgnoresStaleWatermarkBeforeWindowStart() {
        DataSourceTable table = restTable(TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl"))
                .thenReturn(List.of(columnMeasure("pat", DataColumnType.NUMBER)));
        LocalDate from = LocalDate.of(2024, 6, 19);
        // Watermark sits before the requested window start, so the full window is re-fetched from 'from'.
        when(stagingRepository.findMaxAsOfDate("tbl", "CP-1"))
                .thenReturn(Optional.of(LocalDate.of(2023, 1, 1)));
        when(dataApiClient.fetchValues(any(), eq("CP-1"), eq(from), eq(AS_OF), any()))
                .thenReturn(new DataSeriesResponse("CP-1", List.of(
                        new DataPoint(LocalDate.of(2025, 12, 31), Map.of("pat", 20))), List.of(), null));

        service.ingest(table, "CP-1", from, AS_OF);

        verify(dataApiClient).fetchValues(any(), eq("CP-1"), eq(from), eq(AS_OF), any());
    }

    @Test
    void restSeriesSkipsFetchWhenWatermarkAtOrAfterAsOf() {
        DataSourceTable table = restTable(TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl"))
                .thenReturn(List.of(columnMeasure("pat", DataColumnType.NUMBER)));
        LocalDate from = LocalDate.of(2024, 6, 19);
        // Everything through asOf is already staged, so there is nothing newer to fetch.
        when(stagingRepository.findMaxAsOfDate("tbl", "CP-1")).thenReturn(Optional.of(AS_OF));

        assertThat(service.ingest(table, "CP-1", from, AS_OF).rowsStaged()).isZero();
        verify(dataApiClient, never()).fetchValues(any(), any(), any(), any(), any());
        verify(stagingRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void restSeriesSkipsNullDatesUnknownKeysAndNullValues() {
        DataSourceTable table = restTable(TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl"))
                .thenReturn(List.of(columnMeasure("pat", DataColumnType.NUMBER)));
        LocalDate from = LocalDate.of(2024, 6, 19);
        when(stagingRepository.findMaxAsOfDate("tbl", "CP-1")).thenReturn(Optional.empty());
        Map<String, Object> withNulls = new HashMap<>();
        withNulls.put("pat", null);          // registered but null -> skipped
        withNulls.put("unregistered", 99);   // no measure -> skipped
        when(dataApiClient.fetchValues(any(), eq("CP-1"), eq(from), eq(AS_OF), any()))
                .thenReturn(new DataSeriesResponse("CP-1", List.of(
                        new DataPoint(null, Map.of("pat", 5)),                    // null date -> whole point skipped
                        new DataPoint(LocalDate.of(2025, 12, 31), withNulls),     // all cells skipped
                        new DataPoint(LocalDate.of(2026, 3, 31), Map.of("pat", 30))), List.of(), null));

        int written = service.ingest(table, "CP-1", from, AS_OF).rowsStaged();

        assertThat(written).isEqualTo(1);
        verify(stagingRepository).saveAllAndFlush(savedCaptor.capture());
        assertThat(savedCaptor.getValue()).singleElement()
                .satisfies(r -> assertThat(r.getAsOfDate()).isEqualTo(LocalDate.of(2026, 3, 31)));
    }

    @Test
    void restSeriesWithNoPointsWritesNothing() {
        DataSourceTable table = restTable(TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl"))
                .thenReturn(List.of(columnMeasure("pat", DataColumnType.NUMBER)));
        LocalDate from = LocalDate.of(2024, 6, 19);
        when(stagingRepository.findMaxAsOfDate("tbl", "CP-1")).thenReturn(Optional.empty());
        when(dataApiClient.fetchValues(any(), eq("CP-1"), eq(from), eq(AS_OF), any()))
                .thenReturn(new DataSeriesResponse("CP-1", null, null, null));

        assertThat(service.ingest(table, "CP-1", from, AS_OF).rowsStaged()).isZero();
        verify(stagingRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void restPointInTimeFetchesWithNullFrom() {
        DataSourceTable table = restTable(TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl"))
                .thenReturn(List.of(columnMeasure("pat", DataColumnType.NUMBER)));
        // from == asOf -> point-in-time term -> a null 'from' is sent so the source returns one latest point.
        when(dataApiClient.fetchValues(any(), eq("CP-1"), isNull(), eq(AS_OF), any()))
                .thenReturn(new DataSeriesResponse("CP-1",
                        List.of(new DataPoint(AS_OF, Map.of("pat", 12))), List.of(), null));

        int written = service.ingest(table, "CP-1", AS_OF, AS_OF).rowsStaged();

        assertThat(written).isEqualTo(1);
        verify(dataApiClient).fetchValues(any(), eq("CP-1"), isNull(), eq(AS_OF), any());
    }

    @Test
    void restMapsEveryDeclaredType() {
        DataSourceTable table = restTable(TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl")).thenReturn(List.of(
                columnMeasure("n", DataColumnType.NUMBER),
                columnMeasure("s", DataColumnType.STRING),
                columnMeasure("d", DataColumnType.DATE),
                columnMeasure("b", DataColumnType.BOOLEAN)));
        when(dataApiClient.fetchValues(any(), eq("CP-1"), isNull(), eq(AS_OF), any()))
                .thenReturn(new DataSeriesResponse("CP-1", List.of(new DataPoint(AS_OF,
                        Map.of("n", 12.5, "s", "hi", "d", "2026-02-01", "b", true))), List.of(), null));

        service.ingest(table, "CP-1", AS_OF);

        verify(stagingRepository).saveAllAndFlush(savedCaptor.capture());
        List<StagingValue> rows = savedCaptor.getValue();
        assertThat(numFor(rows, "n", AS_OF)).isEqualTo(12.5);
        assertThat(rowFor(rows, "s", AS_OF).getStrValue()).isEqualTo("hi");
        assertThat(rowFor(rows, "d", AS_OF).getDateValue()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(rowFor(rows, "b", AS_OF).getBoolValue()).isTrue();
    }

    @Test
    void noMeasuresShortCircuitsWithoutTouchingStaging() {
        DataSourceTable table = mssqlTable("cp_fin", TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl")).thenReturn(List.of());

        assertThat(service.ingest(table, "CP-1", AS_OF).rowsStaged()).isZero();
        verify(stagingRepository, never()).saveAllAndFlush(any());
        verify(stagingRepository, never())
                .deleteByDataSourceTableIdAndEntityKeyAndAsOfDateIn(any(), any(), anyCollection());
    }

    @Test
    void entityWithNoRowsWritesNothing() {
        DataSourceTable table = mssqlTable("cp_fin", TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl"))
                .thenReturn(List.of(columnMeasure("pat", DataColumnType.NUMBER)));

        assertThat(service.ingest(table, "CP-UNKNOWN", AS_OF).rowsStaged()).isZero();
        verify(stagingRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void anUnreachableSourceIsASourceReadFailureAndStagesNothing() {
        DataSourceTable table = restTable(TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl"))
                .thenReturn(List.of(columnMeasure("pat", DataColumnType.NUMBER)));
        when(dataApiClient.fetchValues(any(), eq("CP-1"), isNull(), eq(AS_OF), any()))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "DATA_API_FAILED",
                        "Data API call failed: 503"));

        assertThatThrownBy(() -> service.ingest(table, "CP-1", AS_OF))
                .isInstanceOf(SourceReadException.class)
                .hasMessageContaining("503");
        verify(stagingRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void rejectsSqlInjectionInTableName() {
        DataSourceTable table = mssqlTable("cp_fin; DROP TABLE cp_fin", TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl"))
                .thenReturn(List.of(columnMeasure("pat", DataColumnType.NUMBER)));

        assertThatThrownBy(() -> service.ingest(table, "CP-1", AS_OF))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("tableName");
        verify(stagingRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void restCoercesEachRawJavaType() {
        DataSourceTable table = restTable(TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl")).thenReturn(List.of(
                columnMeasure("numFromBool", DataColumnType.NUMBER),
                columnMeasure("numFromText", DataColumnType.NUMBER),
                columnMeasure("numFromBlank", DataColumnType.NUMBER),
                columnMeasure("dateFromLocal", DataColumnType.DATE),
                columnMeasure("dateFromTimestamp", DataColumnType.DATE),
                columnMeasure("dateFromDateTime", DataColumnType.DATE),
                columnMeasure("boolFromNumber", DataColumnType.BOOLEAN),
                columnMeasure("boolFromText", DataColumnType.BOOLEAN)));
        // Raw values span every Java type the coercion helpers branch on.
        Map<String, Object> values = Map.of(
                "numFromBool", true,
                "numFromText", "42.5",
                "numFromBlank", "   ",
                "dateFromLocal", LocalDate.of(2026, 5, 1),
                "dateFromTimestamp", java.sql.Timestamp.valueOf("2026-06-02 10:30:00"),
                "dateFromDateTime", LocalDateTime.of(2026, 7, 3, 8, 15),
                "boolFromNumber", 1,
                "boolFromText", "false");
        when(dataApiClient.fetchValues(any(), eq("CP-1"), isNull(), eq(AS_OF), any()))
                .thenReturn(new DataSeriesResponse("CP-1",
                        List.of(new DataPoint(AS_OF, values)), List.of(), null));

        service.ingest(table, "CP-1", AS_OF);

        verify(stagingRepository).saveAllAndFlush(savedCaptor.capture());
        List<StagingValue> rows = savedCaptor.getValue();
        assertThat(numFor(rows, "numFromBool", AS_OF)).isEqualTo(1.0);        // Boolean -> ONE
        assertThat(numFor(rows, "numFromText", AS_OF)).isEqualTo(42.5);       // String -> parsed
        assertThat(rowFor(rows, "numFromBlank", AS_OF).getNumValue()).isNull(); // blank String -> null
        assertThat(rowFor(rows, "dateFromLocal", AS_OF).getDateValue()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(rowFor(rows, "dateFromTimestamp", AS_OF).getDateValue()).isEqualTo(LocalDate.of(2026, 6, 2));
        assertThat(rowFor(rows, "dateFromDateTime", AS_OF).getDateValue()).isEqualTo(LocalDate.of(2026, 7, 3));
        assertThat(rowFor(rows, "boolFromNumber", AS_OF).getBoolValue()).isTrue();   // 1 != 0
        assertThat(rowFor(rows, "boolFromText", AS_OF).getBoolValue()).isFalse();    // "false" parsed
    }

    @Test
    void restSkipsUnregisteredKeysAndNullValues() {
        DataSourceTable table = restTable(TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl")).thenReturn(List.of(
                columnMeasure("known", DataColumnType.NUMBER),
                columnMeasure("nullValued", DataColumnType.NUMBER)));
        Map<String, Object> values = new HashMap<>();
        values.put("known", 7);
        values.put("nullValued", null);   // registered but null -> value-not-null branch is false
        values.put("unregistered", 99);   // no measure -> type-not-null branch is false
        when(dataApiClient.fetchValues(any(), eq("CP-1"), isNull(), eq(AS_OF), any()))
                .thenReturn(new DataSeriesResponse("CP-1",
                        List.of(new DataPoint(AS_OF, values)), List.of(), null));

        int written = service.ingest(table, "CP-1", AS_OF).rowsStaged();

        assertThat(written).isEqualTo(1);
        verify(stagingRepository).saveAllAndFlush(savedCaptor.capture());
        assertThat(savedCaptor.getValue()).singleElement()
                .satisfies(r -> assertThat(r.getItemCode()).isEqualTo("known"));
    }

    @Test
    void restWithNoResolvableKeysSkipsFetchEntirely() {
        DataSourceTable table = restTable(TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl"))
                .thenReturn(List.of(measureWithoutSource(DataColumnType.NUMBER)));

        assertThat(service.ingest(table, "CP-1", AS_OF).rowsStaged()).isZero();
        verify(dataApiClient, never()).fetchValues(any(), any(), any(), any(), any());
        verify(stagingRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void restRoutesToSourceSpecificEndpoint() {
        DataSource source = new DataSource();
        ReflectionTestUtils.setField(source, "id", "src");
        source.setName("REST API");
        source.setType(DataSourceType.REST);
        // WS4 per-source routing: the fetch must be aimed at THIS source's baseUrl/valuesPath.
        source.setConnectionConfig(Map.of("baseUrl", "http://feed.example", "valuesPath", "/v2/values"));
        DataSourceTable table = table(source, "rest_values", TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl"))
                .thenReturn(List.of(columnMeasure("pat", DataColumnType.NUMBER)));
        when(dataApiClient.fetchValues(any(), eq("CP-1"), isNull(), eq(AS_OF), any()))
                .thenReturn(new DataSeriesResponse("CP-1",
                        List.of(new DataPoint(AS_OF, Map.of("pat", 12))), List.of(), null));

        service.ingest(table, "CP-1", AS_OF);

        ArgumentCaptor<RestEndpoint> endpointCaptor = ArgumentCaptor.forClass(RestEndpoint.class);
        verify(dataApiClient).fetchValues(endpointCaptor.capture(), eq("CP-1"), isNull(), eq(AS_OF), any());
        RestEndpoint endpoint = endpointCaptor.getValue();
        assertThat(endpoint.baseUrl()).isEqualTo("http://feed.example");
        assertThat(endpoint.valuesPath()).isEqualTo("/v2/values");
    }

    @Test
    void restOutcomeCarriesSourceChangedAtFromResponse() {
        DataSourceTable table = restTable(TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl"))
                .thenReturn(List.of(columnMeasure("pat", DataColumnType.NUMBER)));
        Instant changed = Instant.parse("2026-06-19T09:30:00Z");
        // WS2(b): the source's change timestamp rides back on the ingest outcome so the orchestrator
        // can stamp it on the control row.
        when(dataApiClient.fetchValues(any(), eq("CP-1"), isNull(), eq(AS_OF), any()))
                .thenReturn(new DataSeriesResponse("CP-1",
                        List.of(new DataPoint(AS_OF, Map.of("pat", 12))), List.of(), changed));

        StagingIngestionService.IngestOutcome outcome = service.ingest(table, "CP-1", AS_OF);

        assertThat(outcome.rowsStaged()).isEqualTo(1);
        assertThat(outcome.sourceChangedAt()).isEqualTo(changed);
    }

    @Test
    void mssqlOutcomeCarriesNoSourceChangedAt() {
        DataSourceTable table = mssqlTable("cp_fin", TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl"))
                .thenReturn(List.of(columnMeasure("pat", DataColumnType.NUMBER)));

        // MSSQL change-detection is deferred, so the outcome never carries a source-changed timestamp.
        StagingIngestionService.IngestOutcome outcome = service.ingest(table, "CP-1", AS_OF);

        assertThat(outcome.rowsStaged()).isEqualTo(1);
        assertThat(outcome.sourceChangedAt()).isNull();
    }

    @Test
    void mssqlColumnarWindowedSkipsNullCellsAndUnmappedMeasures() {
        DataSourceTable table = mssqlTable("cp_fin", TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl")).thenReturn(List.of(
                columnMeasure("pat", DataColumnType.NUMBER),
                columnMeasure("bonus", DataColumnType.NUMBER),    // always NULL -> each cell skipped
                measureWithoutSource(DataColumnType.NUMBER)));    // null sourceColumn -> not selected
        LocalDate from = LocalDate.of(2024, 6, 19);
        when(stagingRepository.findMinAsOfDate("tbl", "CP-1")).thenReturn(Optional.empty());
        when(stagingRepository.findMaxAsOfDate("tbl", "CP-1")).thenReturn(Optional.empty());

        int written = service.ingest(table, "CP-1", from, AS_OF).rowsStaged();

        // Only 'pat' lands (two in-range dates); 'bonus' cells are null; the unmapped measure is ignored.
        assertThat(written).isEqualTo(2);
        verify(stagingRepository).saveAllAndFlush(savedCaptor.capture());
        assertThat(savedCaptor.getValue()).allSatisfy(r -> assertThat(r.getItemCode()).isEqualTo("pat"));
    }

    @Test
    void mssqlColumnarWithNoMappedColumnsWritesNothing() {
        DataSourceTable table = mssqlTable("cp_fin", TableLayout.COLUMNAR);
        when(measureRepository.findByDataSourceTableId("tbl"))
                .thenReturn(List.of(measureWithoutSource(DataColumnType.NUMBER)));

        assertThat(service.ingest(table, "CP-1", AS_OF).rowsStaged()).isZero();
        verify(stagingRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void mssqlKeyValueLandsNullValueRowWithNoTypedColumns() {
        DataSourceTable table = mssqlTable("cp_ratios", TableLayout.KEY_VALUE);
        table.setCodeColumn("ratio_code");
        table.setValueColumn("ratio_value");
        when(measureRepository.findByDataSourceTableId("tbl")).thenReturn(List.of(codeMeasure("NULLR")));

        int written = service.ingest(table, "CP-1", AS_OF).rowsStaged();

        assertThat(written).isEqualTo(1);
        verify(stagingRepository).saveAllAndFlush(savedCaptor.capture());
        StagingValue row = savedCaptor.getValue().get(0);
        assertThat(row.getItemCode()).isEqualTo("NULLR");
        assertThat(row.getNumValue()).isNull();
        assertThat(row.getStrValue()).isNull();
        assertThat(row.getDateValue()).isNull();
        assertThat(row.getBoolValue()).isNull();
    }

    @Test
    void mssqlKeyValueWithNoMappedCodesWritesNothing() {
        DataSourceTable table = mssqlTable("cp_ratios", TableLayout.KEY_VALUE);
        table.setCodeColumn("ratio_code");
        table.setValueColumn("ratio_value");
        when(measureRepository.findByDataSourceTableId("tbl"))
                .thenReturn(List.of(measureWithoutSource(DataColumnType.NUMBER)));

        assertThat(service.ingest(table, "CP-1", AS_OF).rowsStaged()).isZero();
        verify(stagingRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void mssqlReadsSchemaQualifiedTable() {
        DataSourceTable table = mssqlTable("cp_scoped", TableLayout.COLUMNAR);
        table.setSchemaName("fin");
        when(measureRepository.findByDataSourceTableId("tbl"))
                .thenReturn(List.of(columnMeasure("pat", DataColumnType.NUMBER)));

        int written = service.ingest(table, "CP-1", AS_OF).rowsStaged();

        assertThat(written).isEqualTo(1);
        verify(stagingRepository).saveAllAndFlush(savedCaptor.capture());
        assertThat(numFor(savedCaptor.getValue(), "pat", LocalDate.of(2026, 1, 1))).isEqualTo(77.0);
    }

    // --- helpers ---

    private static double numFor(List<StagingValue> rows, String code, LocalDate date) {
        return rowFor(rows, code, date).getNumValue().doubleValue();
    }

    private static StagingValue rowFor(List<StagingValue> rows, String code, LocalDate date) {
        return rows.stream()
                .filter(r -> code.equals(r.getItemCode()) && date.equals(r.getAsOfDate()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + code + " @ " + date));
    }

    private DataSource mssqlSource() {
        DataSource ds = new DataSource();
        ReflectionTestUtils.setField(ds, "id", "src");
        ds.setName("Live MSSQL");
        ds.setType(DataSourceType.MSSQL);
        ds.setConnectionConfig(Map.of("jdbcUrl", LIVE_URL));
        return ds;
    }

    private DataSource restSource() {
        DataSource ds = new DataSource();
        ReflectionTestUtils.setField(ds, "id", "src");
        ds.setName("REST API");
        ds.setType(DataSourceType.REST);
        // Empty config exercises the endpoint defaults (null baseUrl -> global client, default valuesPath).
        ds.setConnectionConfig(Map.of());
        return ds;
    }

    private DataSourceTable mssqlTable(String tableName, TableLayout layout) {
        return table(mssqlSource(), tableName, layout);
    }

    private DataSourceTable restTable(TableLayout layout) {
        return table(restSource(), "rest_values", layout);
    }

    private DataSourceTable table(DataSource source, String tableName, TableLayout layout) {
        DataSourceTable t = new DataSourceTable();
        ReflectionTestUtils.setField(t, "id", "tbl");
        t.setDataSource(source);
        t.setName("Financials");
        t.setTableName(tableName);
        t.setEntityKeyColumn("cp_id");
        t.setAsOfColumn("as_of_date");
        t.setLayout(layout);
        return t;
    }

    private static Measure columnMeasure(String column, DataColumnType type) {
        Measure m = new Measure();
        m.setCode(column.toUpperCase());
        m.setSourceColumn(column);
        m.setDataType(type);
        return m;
    }

    private static Measure codeMeasure(String code) {
        Measure m = new Measure();
        m.setCode(code);
        m.setSourceCode(code);
        m.setDataType(DataColumnType.NUMBER);
        return m;
    }

    /** A measure with neither a source column nor a source code — nothing to read for it. */
    private static Measure measureWithoutSource(DataColumnType type) {
        Measure m = new Measure();
        m.setCode("NOSRC");
        m.setDataType(type);
        return m;
    }
}
