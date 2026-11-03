package com.crisil.covenant.service;

import com.crisil.covenant.dto.ops.HealthReport;
import com.crisil.covenant.dto.ops.SourceHealth;
import com.crisil.covenant.entity.DataSource;
import com.crisil.covenant.repository.DataSourceRepository;
import com.crisil.covenant.repository.DataSourceTableRepository;
import com.crisil.covenant.repository.StagingValueRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Production-support health &amp; connectivity checks over every registered {@link DataSource}. For each
 * source it reuses the same probe the admin "test connection" path uses —
 * {@link ColumnDiscoveryService#discover} (a REST catalog fetch or an MSSQL {@code INFORMATION_SCHEMA}
 * introspection) — timing it and catching any failure so one unreachable source can never break the
 * whole report. Alongside reachability it reports each source's registered table count, total staged
 * rows and last-ingest instant, so a support engineer can tell "reachable but nothing staged" apart
 * from "unreachable".
 *
 * <p>The check performs no writes and no ingestion. The identical roll-up backs the {@code dataSources}
 * Actuator health indicator, so the monitoring signal and the detailed support view never diverge.
 */
@Service
@Transactional(readOnly = true)
public class OpsHealthService {

    private final DataSourceRepository dataSourceRepository;
    private final ColumnDiscoveryService columnDiscovery;
    private final DataSourceTableRepository tableRepository;
    private final StagingValueRepository stagingRepository;

    public OpsHealthService(DataSourceRepository dataSourceRepository,
                            ColumnDiscoveryService columnDiscovery,
                            DataSourceTableRepository tableRepository,
                            StagingValueRepository stagingRepository) {
        this.dataSourceRepository = dataSourceRepository;
        this.columnDiscovery = columnDiscovery;
        this.tableRepository = tableRepository;
        this.stagingRepository = stagingRepository;
    }

    /**
     * Probe every registered source (or only the active ones) and roll the results up into a report.
     *
     * @param activeOnly when {@code true}, probe only sources flagged active; otherwise probe all
     */
    public HealthReport checkDataSources(boolean activeOnly) {
        List<DataSource> sources = activeOnly
                ? dataSourceRepository.findByActiveTrueOrderByNameAsc()
                : dataSourceRepository.findAllByOrderByNameAsc();
        List<SourceHealth> results = new ArrayList<>(sources.size());
        int reachable = 0;
        for (DataSource source : sources) {
            SourceHealth health = probe(source);
            if (health.reachable()) {
                reachable++;
            }
            results.add(health);
        }
        return new HealthReport(Instant.now(), results.size(), reachable,
                reachable == results.size(), results);
    }

    private SourceHealth probe(DataSource source) {
        long tableCount = tableRepository.countByDataSourceId(source.getId());
        long stagingRowCount = stagingRepository.countByDataSourceId(source.getId());
        Instant lastIngestAt =
                stagingRepository.findMaxIngestedAtByDataSourceId(source.getId()).orElse(null);
        long startNanos = System.nanoTime();
        try {
            int columnCount = columnDiscovery.discover(source).size();
            return new SourceHealth(source.getId(), source.getName(), source.getType().name(),
                    source.isActive(), true, elapsedMs(startNanos), columnCount,
                    tableCount, stagingRowCount, lastIngestAt,
                    "Connected — discovered " + columnCount + " column(s)");
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return new SourceHealth(source.getId(), source.getName(), source.getType().name(),
                    source.isActive(), false, elapsedMs(startNanos), 0,
                    tableCount, stagingRowCount, lastIngestAt, message);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
