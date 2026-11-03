package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.crisil.covenant.domain.DataPoint;
import com.crisil.covenant.domain.DataSeriesResponse;
import com.crisil.covenant.entity.DataSource;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.entity.Measure;
import com.crisil.covenant.entity.StagingValue;
import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.enums.DataSourceType;
import com.crisil.covenant.formula.FunctionKind;
import com.crisil.covenant.repository.DataSourceRepository;
import com.crisil.covenant.repository.DataSourceTableRepository;
import com.crisil.covenant.repository.MeasureRepository;
import com.crisil.covenant.repository.StagingValueRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

/**
 * Persistence IT for {@link StagingIngestionService} against a live JPA context (H2 in MSSQL mode).
 * The mock-based {@code StagingIngestionServiceTest} proves the read paths build the right rows, but
 * it mocks {@link StagingValueRepository} — so it can only assert that delete-then-insert is
 * <em>called</em> in order, never that Hibernate <em>executes</em> it in that order against the real
 * {@code uk_staging_value} unique key. That gap is what this IT closes.
 */
@SpringBootTest
@ActiveProfiles("test")
class StagingIngestionServiceIT {

    private static final String ENTITY = "CP-STAGE-IT";
    private static final LocalDate JAN = LocalDate.of(2026, 1, 31);
    private static final LocalDate FEB = LocalDate.of(2026, 2, 28);
    private static final LocalDate AS_OF = LocalDate.of(2026, 3, 31);

    @Autowired private StagingIngestionService service;
    @Autowired private StagingValueRepository stagingRepo;
    @Autowired private MeasureRepository measureRepo;
    @Autowired private DataSourceTableRepository tableRepo;
    @Autowired private DataSourceRepository dataSourceRepo;

    @MockBean private DataApiClient dataApiClient;

    @AfterEach
    void cleanup() {
        stagingRepo.deleteAll();
        measureRepo.deleteAll();
        tableRepo.deleteAll();
        dataSourceRepo.deleteAll();
    }

    /**
     * Re-ingesting an as-of date that is already staged must overwrite it, not fail. The source is
     * asked for the latest observation {@code <= asOf} and keeps answering with the same dated point
     * (real sources do: the latest published quarter does not move just because the assessment date
     * did), so the second pull rewrites exactly the row the first one landed.
     */
    @Test
    void reIngestingAnAlreadyStagedDateOverwritesItInsteadOfViolatingTheUniqueKey() {
        DataSourceTable table = seedRestTable();
        stubLatestPoint(BigDecimal.valueOf(1.85));

        assertThat(service.ingest(table, ENTITY, AS_OF).rowsStaged()).isEqualTo(1);

        // The source now reports a revised value for the same as-of date.
        stubLatestPoint(BigDecimal.valueOf(1.42));
        assertThat(service.ingest(table, ENTITY, AS_OF).rowsStaged()).isEqualTo(1);

        List<StagingValue> staged = stagingRepo.findByDataSourceTableIdAndEntityKey(table.getId(), ENTITY);
        assertThat(staged).hasSize(1);
        assertThat(staged.get(0).getNumValue()).isEqualByComparingTo("1.42");
    }

    /**
     * A widened look-back triggers a reach-back refetch of the whole window, so the delete spans
     * several as-of dates at once and overwrites rows that are already staged. Same collision as
     * above, via the multi-date branch that a real covenant hits when its window grows.
     */
    @Test
    void aWidenedLookBackRefetchesTheWholeWindowWithoutDuplicating() {
        DataSourceTable table = seedRestTable();

        // First pull covers [Feb, Mar].
        stubSeries(Map.of(FEB, BigDecimal.valueOf(1.10), AS_OF, BigDecimal.valueOf(1.20)));
        assertThat(service.ingest(table, ENTITY, FEB, AS_OF).rowsStaged()).isEqualTo(2);

        // The covenant's look-back widens to January: everything staged is refetched, Feb and Mar
        // included, so two of the three inserts land on keys that already exist.
        stubSeries(Map.of(JAN, BigDecimal.valueOf(1.00), FEB, BigDecimal.valueOf(1.11),
                AS_OF, BigDecimal.valueOf(1.22)));
        assertThat(service.ingest(table, ENTITY, JAN, AS_OF).rowsStaged()).isEqualTo(3);

        assertThat(stagingRepo.findByDataSourceTableIdAndEntityKey(table.getId(), ENTITY))
                .hasSize(3)
                .extracting(StagingValue::getAsOfDate, StagingValue::getNumValue)
                .containsExactlyInAnyOrder(tuple(JAN, new BigDecimal("1.000000")),
                        tuple(FEB, new BigDecimal("1.110000")),
                        tuple(AS_OF, new BigDecimal("1.220000")));
    }

    /**
     * The other half of the delete's contract: it is scoped to the dates actually re-read, so a
     * point-in-time re-ingest rewrites its own date and leaves accrued history alone.
     */
    @Test
    void pointInTimeReIngestOverwritesOnlyItsOwnDateAndKeepsEarlierHistory() {
        DataSourceTable table = seedRestTable();
        stubSeries(Map.of(JAN, BigDecimal.valueOf(1.00), FEB, BigDecimal.valueOf(1.10),
                AS_OF, BigDecimal.valueOf(1.20)));
        assertThat(service.ingest(table, ENTITY, JAN, AS_OF).rowsStaged()).isEqualTo(3);

        stubLatestPoint(BigDecimal.valueOf(1.99));
        assertThat(service.ingest(table, ENTITY, AS_OF).rowsStaged()).isEqualTo(1);

        assertThat(stagingRepo.findByDataSourceTableIdAndEntityKey(table.getId(), ENTITY))
                .hasSize(3)
                .extracting(StagingValue::getAsOfDate, StagingValue::getNumValue)
                .containsExactlyInAnyOrder(tuple(JAN, new BigDecimal("1.000000")),
                        tuple(FEB, new BigDecimal("1.100000")),
                        tuple(AS_OF, new BigDecimal("1.990000")));
    }

    /** Stub the point-in-time read: one latest observation on {@link #AS_OF}. */
    private void stubLatestPoint(BigDecimal value) {
        when(dataApiClient.fetchValues(any(), anyString(), isNull(), any(), anyList()))
                .thenReturn(new DataSeriesResponse(ENTITY,
                        List.of(new DataPoint(AS_OF, Map.of("current_ratio", value))),
                        List.of(), null));
    }

    /** Stub the windowed read: one dated point per entry, in date order. */
    private void stubSeries(Map<LocalDate, BigDecimal> byDate) {
        List<DataPoint> points = byDate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new DataPoint(e.getKey(), Map.<String, Object>of("current_ratio", e.getValue())))
                .toList();
        when(dataApiClient.fetchValues(any(), anyString(), any(LocalDate.class), any(), anyList()))
                .thenReturn(new DataSeriesResponse(ENTITY, points, List.of(), null));
    }

    private DataSourceTable seedRestTable() {
        DataSource source = new DataSource();
        source.setName("Staging IT Source");
        source.setType(DataSourceType.REST);
        source.setConnectionConfig(Map.of("baseUrl", "http://localhost:9/never-called"));
        source = dataSourceRepo.saveAndFlush(source);

        DataSourceTable table = new DataSourceTable();
        table.setDataSource(source);
        table.setName("Staging IT Table");
        table.setTableName("staging_it");
        table.setEntityKeyColumn("cp_id");
        table.setAsOfColumn("as_of");
        table = tableRepo.saveAndFlush(table);

        Measure measure = new Measure();
        measure.setCode("STAGE_IT_CURRENT_RATIO");
        measure.setName("Current Ratio");
        measure.setDataSourceTable(table);
        measure.setSourceColumn("current_ratio");
        measure.setDataType(DataColumnType.NUMBER);
        measure.setDefaultAggregation(FunctionKind.LATEST);
        measure.setActive(true);
        measureRepo.saveAndFlush(measure);
        return table;
    }
}
