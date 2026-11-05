package com.crisil.covenant.service;

import com.crisil.covenant.config.AuditingConfig;
import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.dto.DataEventRequest;
import com.crisil.covenant.dto.DataEventResponse;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.DataEvent;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.entity.Measure;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.AssessmentTrigger;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.TableEntityKeyLevel;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.formula.MeasureCatalog;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.DataEventRepository;
import com.crisil.covenant.repository.DataSourceTableRepository;
import com.crisil.covenant.repository.MeasureRepository;
import com.crisil.covenant.repository.ObligorCpMapRepository;
import com.crisil.covenant.util.IdBatches;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * C5 (R1/T8): the Document Processing Service reports that new data exists for an entity (a CP, or an
 * obligor shared by several CPs). Core then
 * <ol>
 *   <li>records the source change on each table of that data source, so the freshness gate re-ingests;</li>
 *   <li>re-assesses every ACTIVE AUTO covenant of the affected CPs whose condition uses a measure from
 *       that data source.</li>
 * </ol>
 * This closes the gap where a late statement waited for the next cron date: the breach run treats an
 * ERROR run as already assessed. Re-delivered events (same source, entity and changedAt) are no-ops.
 */
@Service
public class DataEventService {

    private static final Logger LOG = LoggerFactory.getLogger(DataEventService.class);

    private final DataEventRepository eventRepo;
    private final DataSourceTableRepository tableRepo;
    private final MeasureRepository measureRepo;
    private final ObligorCpMapRepository obligorRepo;
    private final AppliedCovenantRepository appliedRepo;
    private final IngestionOrchestrationService ingestion;
    private final AssessmentService assessmentService;
    private final FormulaEngine formulaEngine;
    private final MeasureCatalogService catalogService;

    public DataEventService(DataEventRepository eventRepo, DataSourceTableRepository tableRepo,
                            MeasureRepository measureRepo, ObligorCpMapRepository obligorRepo,
                            AppliedCovenantRepository appliedRepo, IngestionOrchestrationService ingestion,
                            AssessmentService assessmentService, FormulaEngine formulaEngine,
                            MeasureCatalogService catalogService) {
        this.eventRepo = eventRepo;
        this.tableRepo = tableRepo;
        this.measureRepo = measureRepo;
        this.obligorRepo = obligorRepo;
        this.appliedRepo = appliedRepo;
        this.ingestion = ingestion;
        this.assessmentService = assessmentService;
        this.formulaEngine = formulaEngine;
        this.catalogService = catalogService;
    }

    public DataEventResponse handle(DataEventRequest req) {
        if (eventRepo.existsByDataSourceIdAndEntityKeyAndChangedAt(req.dataSourceId(), req.entityKey(),
                req.changedAt())) {
            return new DataEventResponse(0, true);
        }
        List<DataSourceTable> tables = tableRepo.findByDataSourceIdOrderByNameAsc(req.dataSourceId()).stream()
                .filter(t -> t.getEntityKeyLevel() == req.entityKeyLevel())
                .toList();
        if (tables.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DATA_SOURCE_TABLE_NOT_FOUND",
                    "No " + req.entityKeyLevel() + " table registered for data source " + req.dataSourceId());
        }

        Set<String> measureCodes = new HashSet<>();
        for (DataSourceTable table : tables) {
            ingestion.recordSourceChange(table, req.entityKey(), req.changedAt());
            for (Measure m : measureRepo.findByDataSourceTableId(table.getId())) {
                measureCodes.add(m.getCode().toUpperCase(Locale.ROOT));
            }
        }

        List<String> cpIds = req.entityKeyLevel() == TableEntityKeyLevel.OBLIGOR_ID
                ? obligorRepo.findCpIdsByObligorId(req.entityKey())
                : List.of(req.entityKey());
        int assessed = reassess(cpIds, measureCodes);

        String actor = CurrentUserContext.currentUser().orElse(AuditingConfig.SYSTEM_USER);
        try {
            eventRepo.save(new DataEvent(req.dataSourceId(), req.entityKey(), req.entityKeyLevel(), req.asOfDate(),
                    req.changedAt(), req.documentId(), actor, Instant.now(), assessed));
        } catch (DataIntegrityViolationException raced) {
            // The same event arrived twice at once (an outbox retry, or two Core nodes): the other copy
            // was recorded first, and re-assessing is idempotent, so this one is a duplicate.
            LOG.info("Data event for {} already recorded by a concurrent delivery", safe(req.entityKey()));
            return new DataEventResponse(assessed, true);
        }
        return new DataEventResponse(assessed, false);
    }

    private int reassess(List<String> cpIds, Set<String> measureCodes) {
        if (cpIds.isEmpty() || measureCodes.isEmpty()) {
            return 0;
        }
        MeasureCatalog catalog = catalogService.activeCatalog();
        var ctx = assessmentService.newRunContext();
        int count = 0;
        for (AppliedCovenant ac : IdBatches.fetch(cpIds, appliedRepo::findByCpIdInOrderByIdAsc)) {
            if (ac.getStatus() != AppliedCovenantStatus.ACTIVE
                    || ac.getCovenantMaster().getAssessmentType() != AssessmentType.AUTO
                    || !usesAnyMeasure(ac, measureCodes, catalog)) {
                continue;
            }
            try {
                assessmentService.runAssessment(ac.getId(), AssessmentTrigger.MANUAL, "data-event", ctx);
                count++;
            } catch (RuntimeException ex) {
                LOG.warn("Re-assessment after data event failed for {}: {}",
                        ac.getId().replace('\n', '_').replace('\r', '_'),
                        String.valueOf(ex.getMessage()).replace('\n', '_').replace('\r', '_'));
            }
        }
        return count;
    }

    private boolean usesAnyMeasure(AppliedCovenant ac, Set<String> measureCodes, MeasureCatalog catalog) {
        String condition = ac.getConditionExpression();
        if (condition == null || condition.isBlank()) {
            condition = ac.getCovenantMaster().getConditionExpression();
        }
        try {
            return formulaEngine.compile(condition, catalog).referencedMeasures().stream()
                    .anyMatch(code -> measureCodes.contains(code.toUpperCase(Locale.ROOT)));
        } catch (RuntimeException ex) {
            return false; // a condition that no longer compiles is surfaced by the normal assessment path
        }
    }

    private static String safe(String value) {
        return value == null ? null : value.replace('\n', '_').replace('\r', '_');
    }
}
