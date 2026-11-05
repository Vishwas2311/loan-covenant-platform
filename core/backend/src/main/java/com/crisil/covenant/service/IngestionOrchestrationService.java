package com.crisil.covenant.service;

import com.crisil.covenant.config.IngestionProperties;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.entity.IngestionState;
import com.crisil.covenant.enums.IngestStatus;
import com.crisil.covenant.exception.SourceReadException;
import com.crisil.covenant.repository.IngestionStateRepository;
import com.crisil.covenant.repository.StagingValueRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-DS-13: the freshness gate over {@link StagingIngestionService}. Every candidate ingestion is a
 * <em>decision</em> — ingest just-ahead-of-need, only when the staged copy is missing, stale, or the
 * source has changed — rather than a blind fetch. This turns US-DS-11's fetch-per-evaluation into a
 * gated read: the first pull for an entity lands the copy, and the ~500 covenants that follow on the
 * same entity find it fresh and skip the source entirely.
 *
 * <p>The decision reads a single {@code cov_ingestion_state} row per {@code (table, entity)} so the
 * gate never scans the staging store. Three inputs drive it (see {@link #decide}):
 * <ul>
 *   <li><b>required_as_of</b> — the ceiling the caller needs (assessment date / widest window);</li>
 *   <li><b>staged_as_of</b> — the ceiling we last ingested to for the entity;</li>
 *   <li><b>source_changed_at</b> — when the source last revised data (CDC / force-refresh), and a
 *       per-data-class TTL floor for sources that cannot report change.</li>
 * </ul>
 */
@Service
public class IngestionOrchestrationService {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionOrchestrationService.class);

    private final IngestionStateRepository stateRepository;
    private final StagingIngestionService stagingIngestionService;
    private final StagingValueRepository stagingValueRepository;
    private final IngestionProperties properties;

    public IngestionOrchestrationService(IngestionStateRepository stateRepository,
                                         StagingIngestionService stagingIngestionService,
                                         StagingValueRepository stagingValueRepository,
                                         IngestionProperties properties) {
        this.stateRepository = stateRepository;
        this.stagingIngestionService = stagingIngestionService;
        this.stagingValueRepository = stagingValueRepository;
        this.properties = properties;
    }

    /** The outcome of the freshness gate: whether to ingest, and a human/audit-readable reason. */
    public record Decision(boolean ingest, String reason) { }

    /**
     * Ingest {@code entityKey}'s data for one table covering {@code [from, requiredAsOf]} only if the
     * freshness gate says the staged copy is missing, stale, or the source changed; otherwise skip. The
     * decision outcome is recorded on the control row either way. A failed extract is recorded as
     * {@link IngestStatus#FAILED} and re-thrown so the caller (evaluation or the pre-stage sweep) can
     * isolate the failing entity without losing the signal.
     *
     * @return {@code true} when an extract was issued, {@code false} when the gate skipped it.
     */
    @Transactional(noRollbackFor = SourceReadException.class)
    public boolean ingestIfStale(DataSourceTable table, String entityKey, LocalDate from,
                                 LocalDate requiredAsOf) {
        Instant now = Instant.now();
        IngestionState state = stateRepository
                .findByDataSourceTableIdAndEntityKey(table.getId(), entityKey)
                .orElse(null);
        Decision decision = decide(state, requiredAsOf, now);
        if (state == null) {
            state = newState(table, entityKey);
        }
        // Staging-presence override: the gate can report "fresh" from the control row alone, but if
        // nothing is actually staged for the entity the copy cannot satisfy any read — the freshness
        // trap that surfaces as "Missing values for measures: [...]". Re-ingest rather than trust a
        // control row that outlived its staged rows. Only probed on the skip branch, so the common
        // ingest path (missing/stale/source-changed/ttl) pays no extra query.
        if (!decision.ingest() && stagingIsEmpty(table, entityKey)) {
            LOG.info("Ingesting despite '{}' gate for {}: staging is empty", scrub(decision.reason()),
                    safeKey(table, entityKey));
            decision = new Decision(true, "staging-empty");
        }
        if (!decision.ingest()) {
            state.setLastStatus(IngestStatus.SKIPPED);
            stateRepository.save(state);
            return false;
        }
        StagingIngestionService.IngestOutcome outcome;
        try {
            outcome = stagingIngestionService.ingest(table, entityKey, from, requiredAsOf);
        } catch (RuntimeException ex) {
            state.setLastStatus(IngestStatus.FAILED);
            stateRepository.save(state);
            throw ex;
        }
        state.setStagedAsOf(requiredAsOf);
        state.setStagedIngestedAt(now);
        state.setLastStatus(IngestStatus.OK);
        state.setNextEligibleAt(now.plus(properties.getTtlHours(), ChronoUnit.HOURS));
        // Stamp the source's change timestamp coherently on the SAME control row this success updates
        // (REST only; MSSQL carries null and leaves it untouched — change-detection is deferred). A
        // second self-cancelling transaction is thereby avoided; a later decide() re-pulls via
        // source-changed once the source reports a change newer than this staging.
        if (outcome.sourceChangedAt() != null) {
            state.setSourceChangedAt(outcome.sourceChangedAt());
        }
        stateRepository.save(state);
        return true;
    }

    /**
     * The core gate. Ingest when the staged copy cannot satisfy demand:
     * <ol>
     *   <li>no state / no staged ceiling yet — first ever pull;</li>
     *   <li>{@code staged_as_of < required_as_of} — demand advanced past what we hold;</li>
     *   <li>{@code source_changed_at > staged_ingested_at} — the source revised data since we staged;</li>
     *   <li>the max-staleness TTL has elapsed — safety re-pull for sources with no change signal.</li>
     * </ol>
     * Otherwise skip.
     */
    public Decision decide(IngestionState state, LocalDate requiredAsOf, Instant now) {
        if (state == null || state.getStagedAsOf() == null) {
            return new Decision(true, "missing");
        }
        if (state.getStagedAsOf().isBefore(requiredAsOf)) {
            return new Decision(true, "stale");
        }
        if (sourceChangedSinceStaged(state)) {
            return new Decision(true, "source-changed");
        }
        if (ttlElapsed(state, now)) {
            return new Decision(true, "ttl");
        }
        return new Decision(false, "fresh");
    }

    private static boolean sourceChangedSinceStaged(IngestionState state) {
        return state.getSourceChangedAt() != null
                && state.getStagedIngestedAt() != null
                && state.getSourceChangedAt().isAfter(state.getStagedIngestedAt());
    }

    private static boolean ttlElapsed(IngestionState state, Instant now) {
        return state.getNextEligibleAt() != null && !now.isBefore(state.getNextEligibleAt());
    }

    /**
     * When the stored copy for {@code entityKey} in {@code table} was last refreshed, if one exists. An
     * assessment whose refresh failed uses this copy instead (the resolver still refuses a value older
     * than the measure's max age) and tells the reader the figures may be stale.
     */
    @Transactional(readOnly = true)
    public Optional<Instant> storedCopyAt(DataSourceTable table, String entityKey) {
        return stateRepository.findByDataSourceTableIdAndEntityKey(table.getId(), entityKey)
                .filter(s -> s.getStagedAsOf() != null && s.getStagedIngestedAt() != null)
                .map(IngestionState::getStagedIngestedAt);
    }

    /**
     * On-demand override: stamp {@code source_changed_at = now} so the next gate decision for this
     * entity ingests regardless of staged as-of (restatements, interactive single-CP refresh).
     */
    @Transactional
    public void forceRefresh(DataSourceTable table, String entityKey) {
        Instant now = Instant.now();
        IngestionState state = stateRepository
                .findByDataSourceTableIdAndEntityKey(table.getId(), entityKey)
                .orElseGet(() -> newState(table, entityKey));
        state.setSourceChangedAt(now);
        stateRepository.save(state);
        LOG.info("Force-refresh requested for {}", safeKey(table, entityKey));
    }

    /**
     * Record a source change signal (CDC / {@code last_updated} feed) so the gate re-pulls the affected
     * window on its next decision when {@code changedAt} is newer than the staged copy.
     */
    @Transactional
    public void recordSourceChange(DataSourceTable table, String entityKey, Instant changedAt) {
        if (changedAt == null) {
            return;
        }
        IngestionState state = stateRepository
                .findByDataSourceTableIdAndEntityKey(table.getId(), entityKey)
                .orElseGet(() -> newState(table, entityKey));
        state.setSourceChangedAt(changedAt);
        stateRepository.save(state);
    }

    private boolean stagingIsEmpty(DataSourceTable table, String entityKey) {
        return stagingValueRepository
                .countByDataSourceTableIdAndEntityKey(table.getId(), entityKey) == 0;
    }

    /** Strip CR/LF from a value before it is interpolated into a log line (log-forging guard). */
    private static String scrub(String raw) {
        return raw.replace('\n', '_').replace('\r', '_');
    }

    /** CR/LF-sanitised {@code table/entity} key for safe log interpolation. */
    private static String safeKey(DataSourceTable table, String entityKey) {
        return scrub(table.getId() + "/" + entityKey);
    }

    private static IngestionState newState(DataSourceTable table, String entityKey) {
        IngestionState state = new IngestionState();
        state.setDataSourceTable(table);
        state.setEntityKey(entityKey);
        return state;
    }
}
