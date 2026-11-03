package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.crisil.covenant.config.IngestionProperties;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.entity.IngestionState;
import com.crisil.covenant.enums.IngestStatus;
import com.crisil.covenant.repository.IngestionStateRepository;
import com.crisil.covenant.repository.StagingValueRepository;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * US-DS-13: the freshness-gate decision and its side effects. Covers each branch of {@link
 * IngestionOrchestrationService#decide} and the record-outcome paths of {@code ingestIfStale},
 * {@code forceRefresh}, and {@code recordSourceChange}.
 */
@ExtendWith(MockitoExtension.class)
class IngestionOrchestrationServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 30);
    private static final Instant NOW = Instant.parse("2026-07-21T09:00:00Z");

    @Mock private IngestionStateRepository stateRepository;
    @Mock private StagingIngestionService stagingIngestionService;
    @Mock private StagingValueRepository stagingValueRepository;
    @Mock private IngestionProperties properties;
    @InjectMocks private IngestionOrchestrationService service;

    private DataSourceTable table;

    @BeforeEach
    void setUp() {
        table = tableWithId("tbl-1");
        lenient().when(properties.getTtlHours()).thenReturn(24L);
        lenient().when(stateRepository.save(any(IngestionState.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // --- decide() branches ---

    @Test
    void decideIngestsWhenNoState() {
        IngestionOrchestrationService.Decision d = service.decide(null, AS_OF, NOW);
        assertThat(d.ingest()).isTrue();
        assertThat(d.reason()).isEqualTo("missing");
    }

    @Test
    void decideIngestsWhenNoStagedCeiling() {
        IngestionState state = new IngestionState();
        state.setStagedAsOf(null);
        IngestionOrchestrationService.Decision d = service.decide(state, AS_OF, NOW);
        assertThat(d.ingest()).isTrue();
        assertThat(d.reason()).isEqualTo("missing");
    }

    @Test
    void decideIngestsWhenStagedCeilingIsBehindDemand() {
        IngestionState state = staged(AS_OF.minusDays(1), NOW.minus(1, ChronoUnit.HOURS));
        IngestionOrchestrationService.Decision d = service.decide(state, AS_OF, NOW);
        assertThat(d.ingest()).isTrue();
        assertThat(d.reason()).isEqualTo("stale");
    }

    @Test
    void decideIngestsWhenSourceChangedSinceStaged() {
        IngestionState state = staged(AS_OF, NOW.minus(2, ChronoUnit.HOURS));
        state.setSourceChangedAt(NOW.minus(1, ChronoUnit.HOURS)); // change after we staged
        state.setNextEligibleAt(NOW.plus(1, ChronoUnit.HOURS));   // TTL not yet elapsed
        IngestionOrchestrationService.Decision d = service.decide(state, AS_OF, NOW);
        assertThat(d.ingest()).isTrue();
        assertThat(d.reason()).isEqualTo("source-changed");
    }

    @Test
    void decideIngestsWhenTtlElapsed() {
        IngestionState state = staged(AS_OF, NOW.minus(2, ChronoUnit.HOURS));
        state.setNextEligibleAt(NOW.minus(1, ChronoUnit.HOURS)); // eligible again
        IngestionOrchestrationService.Decision d = service.decide(state, AS_OF, NOW);
        assertThat(d.ingest()).isTrue();
        assertThat(d.reason()).isEqualTo("ttl");
    }

    @Test
    void decideSkipsWhenFresh() {
        IngestionState state = staged(AS_OF, NOW.minus(2, ChronoUnit.HOURS));
        state.setNextEligibleAt(NOW.plus(1, ChronoUnit.HOURS));
        IngestionOrchestrationService.Decision d = service.decide(state, AS_OF, NOW);
        assertThat(d.ingest()).isFalse();
        assertThat(d.reason()).isEqualTo("fresh");
    }

    @Test
    void decideTreatsAStaleSourceChangeAsNotChanged() {
        // source_changed_at older than staged_ingested_at ⇒ our copy already reflects it.
        IngestionState state = staged(AS_OF, NOW.minus(1, ChronoUnit.HOURS));
        state.setSourceChangedAt(NOW.minus(2, ChronoUnit.HOURS));
        state.setNextEligibleAt(NOW.plus(1, ChronoUnit.HOURS));
        IngestionOrchestrationService.Decision d = service.decide(state, AS_OF, NOW);
        assertThat(d.ingest()).isFalse();
        assertThat(d.reason()).isEqualTo("fresh");
    }

    // --- ingestIfStale() outcomes ---

    @Test
    void ingestIfStaleFetchesAndRecordsOkWhenMissing() {
        when(stateRepository.findByDataSourceTableIdAndEntityKey("tbl-1", "CP-1"))
                .thenReturn(Optional.empty());
        when(stagingIngestionService.ingest(table, "CP-1", AS_OF.minusYears(1), AS_OF))
                .thenReturn(new StagingIngestionService.IngestOutcome(1, null));

        boolean ingested = service.ingestIfStale(table, "CP-1", AS_OF.minusYears(1), AS_OF);

        assertThat(ingested).isTrue();
        verify(stagingIngestionService).ingest(table, "CP-1", AS_OF.minusYears(1), AS_OF);
        IngestionState saved = captureSaved();
        assertThat(saved.getLastStatus()).isEqualTo(IngestStatus.OK);
        assertThat(saved.getStagedAsOf()).isEqualTo(AS_OF);
        assertThat(saved.getStagedIngestedAt()).isNotNull();
        assertThat(saved.getNextEligibleAt())
                .isEqualTo(saved.getStagedIngestedAt().plus(24, ChronoUnit.HOURS));
        assertThat(saved.getEntityKey()).isEqualTo("CP-1");
        assertThat(saved.getDataSourceTable()).isSameAs(table);
    }

    @Test
    void ingestIfStaleSkipsAndRecordsSkippedWhenFresh() {
        IngestionState fresh = staged(AS_OF, NOW.minus(1, ChronoUnit.HOURS));
        fresh.setNextEligibleAt(Instant.now().plus(24, ChronoUnit.HOURS));
        when(stateRepository.findByDataSourceTableIdAndEntityKey("tbl-1", "CP-1"))
                .thenReturn(Optional.of(fresh));
        when(stagingValueRepository.countByDataSourceTableIdAndEntityKey("tbl-1", "CP-1"))
                .thenReturn(1L); // staging present → the fresh gate stands

        boolean ingested = service.ingestIfStale(table, "CP-1", AS_OF.minusYears(1), AS_OF);

        assertThat(ingested).isFalse();
        verifyNoInteractions(stagingIngestionService);
        assertThat(captureSaved().getLastStatus()).isEqualTo(IngestStatus.SKIPPED);
    }

    @Test
    void ingestIfStaleIngestsWhenGateFreshButStagingEmpty() {
        // The staleness trap: a control row claims fresh, but nothing is actually staged for the
        // entity. The presence override must re-ingest rather than skip against an empty copy.
        IngestionState fresh = staged(AS_OF, NOW.minus(1, ChronoUnit.HOURS));
        fresh.setNextEligibleAt(Instant.now().plus(24, ChronoUnit.HOURS));
        when(stateRepository.findByDataSourceTableIdAndEntityKey("tbl-1", "CP-1"))
                .thenReturn(Optional.of(fresh));
        when(stagingValueRepository.countByDataSourceTableIdAndEntityKey("tbl-1", "CP-1"))
                .thenReturn(0L); // nothing staged despite the fresh control row
        when(stagingIngestionService.ingest(table, "CP-1", AS_OF.minusYears(1), AS_OF))
                .thenReturn(new StagingIngestionService.IngestOutcome(1, null));

        boolean ingested = service.ingestIfStale(table, "CP-1", AS_OF.minusYears(1), AS_OF);

        assertThat(ingested).isTrue();
        verify(stagingIngestionService).ingest(table, "CP-1", AS_OF.minusYears(1), AS_OF);
        assertThat(captureSaved().getLastStatus()).isEqualTo(IngestStatus.OK);
    }

    @Test
    void ingestIfStaleRecordsFailedAndRethrowsOnExtractFailure() {
        when(stateRepository.findByDataSourceTableIdAndEntityKey("tbl-1", "CP-1"))
                .thenReturn(Optional.empty());
        doThrow(new RuntimeException("live source down"))
                .when(stagingIngestionService).ingest(any(), anyString(), any(), any());

        assertThatThrownBy(() -> service.ingestIfStale(table, "CP-1", AS_OF, AS_OF))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("live source down");

        IngestionState saved = captureSaved();
        assertThat(saved.getLastStatus()).isEqualTo(IngestStatus.FAILED);
        assertThat(saved.getStagedAsOf()).isNull(); // watermark not advanced on failure
    }

    @Test
    void ingestIfStaleStampsSourceChangedAtFromRestOutcome() {
        // WS2(b): a REST ingest reports when the source last changed; the orchestrator stamps it on the
        // SAME control row it marks OK, so a later decide() can re-pull once the source reports a change
        // newer than this staging — no second self-cancelling transaction.
        when(stateRepository.findByDataSourceTableIdAndEntityKey("tbl-1", "CP-1"))
                .thenReturn(Optional.empty());
        Instant changedAt = NOW.minus(30, ChronoUnit.MINUTES);
        when(stagingIngestionService.ingest(table, "CP-1", AS_OF.minusYears(1), AS_OF))
                .thenReturn(new StagingIngestionService.IngestOutcome(5, changedAt));

        boolean ingested = service.ingestIfStale(table, "CP-1", AS_OF.minusYears(1), AS_OF);

        assertThat(ingested).isTrue();
        IngestionState saved = captureSaved();
        assertThat(saved.getSourceChangedAt()).isEqualTo(changedAt);
        assertThat(saved.getLastStatus()).isEqualTo(IngestStatus.OK);
    }

    @Test
    void ingestIfStaleLeavesSourceChangedAtUnchangedForMssqlNullOutcome() {
        // MSSQL change-detection is deferred: a null outcome timestamp must not clobber the existing
        // source_changed_at already on the control row (MSSQL freshness stays TTL-bounded).
        Instant priorChange = NOW.minus(10, ChronoUnit.HOURS);
        IngestionState stale = staged(AS_OF.minusDays(1), NOW.minus(5, ChronoUnit.HOURS));
        stale.setSourceChangedAt(priorChange);
        when(stateRepository.findByDataSourceTableIdAndEntityKey("tbl-1", "CP-1"))
                .thenReturn(Optional.of(stale));
        when(stagingIngestionService.ingest(table, "CP-1", AS_OF.minusYears(1), AS_OF))
                .thenReturn(new StagingIngestionService.IngestOutcome(2, null));

        service.ingestIfStale(table, "CP-1", AS_OF.minusYears(1), AS_OF);

        assertThat(captureSaved().getSourceChangedAt()).isEqualTo(priorChange);
    }

    // --- forceRefresh() / recordSourceChange() ---

    @Test
    void forceRefreshStampsSourceChangedOnExistingState() {
        IngestionState existing = staged(AS_OF, NOW.minus(5, ChronoUnit.HOURS));
        when(stateRepository.findByDataSourceTableIdAndEntityKey("tbl-1", "CP-1"))
                .thenReturn(Optional.of(existing));

        service.forceRefresh(table, "CP-1");

        IngestionState saved = captureSaved();
        assertThat(saved.getSourceChangedAt()).isNotNull();
        assertThat(saved).isSameAs(existing);
    }

    @Test
    void forceRefreshCreatesStateWhenNoneExists() {
        when(stateRepository.findByDataSourceTableIdAndEntityKey("tbl-1", "CP-1"))
                .thenReturn(Optional.empty());

        service.forceRefresh(table, "CP-1");

        IngestionState saved = captureSaved();
        assertThat(saved.getId()).isNull(); // freshly built, not yet persisted
        assertThat(saved.getEntityKey()).isEqualTo("CP-1");
        assertThat(saved.getSourceChangedAt()).isNotNull();
    }

    @Test
    void recordSourceChangeIsNoOpForNullTimestamp() {
        service.recordSourceChange(table, "CP-1", null);
        verifyNoInteractions(stateRepository);
    }

    @Test
    void recordSourceChangeStampsProvidedTimestamp() {
        Instant changedAt = NOW.minus(3, ChronoUnit.HOURS);
        when(stateRepository.findByDataSourceTableIdAndEntityKey("tbl-1", "CP-1"))
                .thenReturn(Optional.empty());

        service.recordSourceChange(table, "CP-1", changedAt);

        assertThat(captureSaved().getSourceChangedAt()).isEqualTo(changedAt);
    }

    private IngestionState captureSaved() {
        ArgumentCaptor<IngestionState> captor = ArgumentCaptor.forClass(IngestionState.class);
        verify(stateRepository).save(captor.capture());
        return captor.getValue();
    }

    private static IngestionState staged(LocalDate stagedAsOf, Instant stagedIngestedAt) {
        IngestionState state = new IngestionState();
        state.setStagedAsOf(stagedAsOf);
        state.setStagedIngestedAt(stagedIngestedAt);
        return state;
    }

    private static DataSourceTable tableWithId(String id) {
        DataSourceTable t = new DataSourceTable();
        try {
            Field f = DataSourceTable.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(t, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return t;
    }
}
