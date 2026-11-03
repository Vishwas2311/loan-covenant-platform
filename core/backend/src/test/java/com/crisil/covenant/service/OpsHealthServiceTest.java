package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.crisil.covenant.domain.DataColumn;
import com.crisil.covenant.dto.ops.HealthReport;
import com.crisil.covenant.dto.ops.SourceHealth;
import com.crisil.covenant.entity.DataSource;
import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.enums.DataSourceType;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.repository.DataSourceRepository;
import com.crisil.covenant.repository.DataSourceTableRepository;
import com.crisil.covenant.repository.StagingValueRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link OpsHealthService}: each active (or all) data source is probed via
 * {@link ColumnDiscoveryService#discover}, a success is reported UP with the discovered column count
 * and per-source table/staging/last-ingest metadata, and any probe failure is caught and reported
 * DOWN without breaking the rest of the report.
 */
@ExtendWith(MockitoExtension.class)
class OpsHealthServiceTest {

    @Mock private DataSourceRepository dataSourceRepository;
    @Mock private ColumnDiscoveryService columnDiscovery;
    @Mock private DataSourceTableRepository tableRepository;
    @Mock private StagingValueRepository stagingRepository;
    @InjectMocks private OpsHealthService service;

    @Test
    void reachableActiveSourceIsReportedUpWithColumnAndStagingCounts() {
        DataSource src = source("ds-1", "Alpha REST", DataSourceType.REST, true);
        Instant lastIngest = Instant.parse("2026-07-20T10:15:30Z");
        when(dataSourceRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(src));
        when(tableRepository.countByDataSourceId("ds-1")).thenReturn(2L);
        when(stagingRepository.countByDataSourceId("ds-1")).thenReturn(42L);
        when(stagingRepository.findMaxIngestedAtByDataSourceId("ds-1"))
                .thenReturn(Optional.of(lastIngest));
        when(columnDiscovery.discover(src)).thenReturn(List.of(
                DataColumn.of("dscr", "DSCR", DataColumnType.NUMBER),
                DataColumn.of("rev", "Revenue", DataColumnType.NUMBER)));

        HealthReport report = service.checkDataSources(true);

        assertThat(report.sourceCount()).isEqualTo(1);
        assertThat(report.reachableCount()).isEqualTo(1);
        assertThat(report.allReachable()).isTrue();
        assertThat(report.checkedAt()).isNotNull();
        SourceHealth health = report.sources().get(0);
        assertThat(health.id()).isEqualTo("ds-1");
        assertThat(health.name()).isEqualTo("Alpha REST");
        assertThat(health.type()).isEqualTo("REST");
        assertThat(health.active()).isTrue();
        assertThat(health.reachable()).isTrue();
        assertThat(health.columnCount()).isEqualTo(2);
        assertThat(health.tableCount()).isEqualTo(2L);
        assertThat(health.stagingRowCount()).isEqualTo(42L);
        assertThat(health.lastIngestAt()).isEqualTo(lastIngest);
        assertThat(health.message()).isEqualTo("Connected — discovered 2 column(s)");
        assertThat(health.latencyMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void unreachableSourceIsReportedDownWithProbeErrorMessage() {
        DataSource src = source("ds-2", "Beta MSSQL", DataSourceType.MSSQL, true);
        when(dataSourceRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(src));
        when(tableRepository.countByDataSourceId("ds-2")).thenReturn(1L);
        when(stagingRepository.countByDataSourceId("ds-2")).thenReturn(0L);
        when(stagingRepository.findMaxIngestedAtByDataSourceId("ds-2")).thenReturn(Optional.empty());
        when(columnDiscovery.discover(src)).thenThrow(new ApiException(
                HttpStatus.BAD_REQUEST, "DATA_SOURCE_CONFIG_MISSING", "missing jdbcUrl"));

        HealthReport report = service.checkDataSources(true);

        assertThat(report.reachableCount()).isZero();
        assertThat(report.allReachable()).isFalse();
        SourceHealth health = report.sources().get(0);
        assertThat(health.reachable()).isFalse();
        assertThat(health.type()).isEqualTo("MSSQL");
        assertThat(health.columnCount()).isZero();
        assertThat(health.tableCount()).isEqualTo(1L);
        assertThat(health.stagingRowCount()).isZero();
        assertThat(health.lastIngestAt()).isNull();
        assertThat(health.message()).isEqualTo("missing jdbcUrl");
    }

    @Test
    void nullProbeErrorMessageFallsBackToExceptionClassName() {
        DataSource src = source("ds-3", "Gamma", DataSourceType.MSSQL, true);
        when(dataSourceRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(src));
        when(tableRepository.countByDataSourceId("ds-3")).thenReturn(0L);
        when(stagingRepository.countByDataSourceId("ds-3")).thenReturn(0L);
        when(stagingRepository.findMaxIngestedAtByDataSourceId("ds-3")).thenReturn(Optional.empty());
        when(columnDiscovery.discover(src)).thenThrow(new IllegalStateException());

        HealthReport report = service.checkDataSources(true);

        assertThat(report.sources().get(0).message()).isEqualTo("IllegalStateException");
        assertThat(report.sources().get(0).reachable()).isFalse();
    }

    @Test
    void activeOnlyFalseProbesAllSourcesAndCountsReachableAcrossMixedResults() {
        DataSource up = source("ds-a", "A", DataSourceType.REST, true);
        DataSource down = source("ds-b", "B", DataSourceType.MSSQL, false);
        when(dataSourceRepository.findAllByOrderByNameAsc()).thenReturn(List.of(up, down));
        when(tableRepository.countByDataSourceId("ds-a")).thenReturn(1L);
        when(stagingRepository.countByDataSourceId("ds-a")).thenReturn(5L);
        when(stagingRepository.findMaxIngestedAtByDataSourceId("ds-a")).thenReturn(Optional.empty());
        when(columnDiscovery.discover(up))
                .thenReturn(List.of(DataColumn.of("x", "X", DataColumnType.STRING)));
        when(tableRepository.countByDataSourceId("ds-b")).thenReturn(0L);
        when(stagingRepository.countByDataSourceId("ds-b")).thenReturn(0L);
        when(stagingRepository.findMaxIngestedAtByDataSourceId("ds-b")).thenReturn(Optional.empty());
        when(columnDiscovery.discover(down)).thenThrow(new RuntimeException("connection refused"));

        HealthReport report = service.checkDataSources(false);

        assertThat(report.sourceCount()).isEqualTo(2);
        assertThat(report.reachableCount()).isEqualTo(1);
        assertThat(report.allReachable()).isFalse();
        assertThat(report.sources()).extracting(SourceHealth::id).containsExactly("ds-a", "ds-b");
        assertThat(report.sources().get(0).reachable()).isTrue();
        assertThat(report.sources().get(1).reachable()).isFalse();
        assertThat(report.sources().get(1).active()).isFalse();
        assertThat(report.sources().get(1).message()).isEqualTo("connection refused");
    }

    @Test
    void emptyRegistryIsVacuouslyAllReachable() {
        when(dataSourceRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of());

        HealthReport report = service.checkDataSources(true);

        assertThat(report.sourceCount()).isZero();
        assertThat(report.reachableCount()).isZero();
        assertThat(report.allReachable()).isTrue();
        assertThat(report.sources()).isEmpty();
    }

    private static DataSource source(String id, String name, DataSourceType type, boolean active) {
        DataSource src = new DataSource();
        ReflectionTestUtils.setField(src, "id", id);
        src.setName(name);
        src.setType(type);
        src.setActive(active);
        return src;
    }
}
