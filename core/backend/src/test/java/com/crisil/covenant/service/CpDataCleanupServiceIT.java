package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.AppliedCovenantAudit;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.entity.AssessmentThreshold;
import com.crisil.covenant.entity.ComplianceCertificate;
import com.crisil.covenant.entity.CovenantAttachment;
import com.crisil.covenant.entity.CovenantDisposition;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.entity.CovenantRecommendation;
import com.crisil.covenant.entity.CovenantStatusAudit;
import com.crisil.covenant.entity.CreditProposalCacheEntry;
import com.crisil.covenant.entity.DataSource;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.entity.IngestionState;
import com.crisil.covenant.entity.MonitoringSchedule;
import com.crisil.covenant.entity.ObligorCpMap;
import com.crisil.covenant.entity.OpsAudit;
import com.crisil.covenant.entity.StagingValue;
import com.crisil.covenant.entity.ThresholdAudit;
import com.crisil.covenant.dto.ops.CpCleanupResult;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.enums.CovenantStatusSource;
import com.crisil.covenant.enums.DataSourceType;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.enums.OpsAuditOutcome;
import com.crisil.covenant.enums.ThresholdSource;
import com.crisil.covenant.formula.FormulaType;
import com.crisil.covenant.repository.AppliedCovenantAuditRepository;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.AssessmentRepository;
import com.crisil.covenant.repository.ComplianceCertificateRepository;
import com.crisil.covenant.repository.CovenantAttachmentRepository;
import com.crisil.covenant.repository.CovenantDispositionRepository;
import com.crisil.covenant.repository.CovenantMasterRepository;
import com.crisil.covenant.repository.CovenantRecommendationRepository;
import com.crisil.covenant.repository.CovenantStatusAuditRepository;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import com.crisil.covenant.repository.DataSourceRepository;
import com.crisil.covenant.repository.DataSourceTableRepository;
import com.crisil.covenant.repository.IngestionStateRepository;
import com.crisil.covenant.repository.MonitoringScheduleRepository;
import com.crisil.covenant.repository.ObligorCpMapRepository;
import com.crisil.covenant.repository.OpsAuditRepository;
import com.crisil.covenant.repository.StagingValueRepository;
import com.crisil.covenant.repository.ThresholdAuditRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end persistence IT for {@link CpDataCleanupService} against H2 in MSSQL mode. Where the
 * mock-based {@code CpDataCleanupServiceTest} proves the orchestration branches, this drives the real
 * {@code @Modifying} bulk-delete JPQL through a live schema so we catch anything the mocks can't:
 * malformed queries (Hibernate parses them at boot), a wrong FK delete order (H2 rejects a parent
 * delete while children reference it), and the assessment-threshold subquery actually resolving. It
 * also confirms the audit row commits in its own {@code REQUIRES_NEW} transaction.
 */
@SpringBootTest
@ActiveProfiles("test")
class CpDataCleanupServiceIT {

    private static final String CP = "CP-CLEAN-1";
    private static final String SIBLING_CP = "CP-CLEAN-2";
    private static final String OBLIGOR = "OBL-CLEAN-1";

    @Autowired private CpDataCleanupService service;
    @Autowired private OpsAuditRepository opsAuditRepo;
    @Autowired private CovenantMasterRepository masterRepo;
    @Autowired private AppliedCovenantRepository appliedRepo;
    @Autowired private AssessmentRepository assessmentRepo;
    @Autowired private MonitoringScheduleRepository scheduleRepo;
    @Autowired private AppliedCovenantAuditRepository appliedAuditRepo;
    @Autowired private CovenantStatusAuditRepository statusAuditRepo;
    @Autowired private CovenantDispositionRepository dispositionRepo;
    @Autowired private CovenantAttachmentRepository attachmentRepo;
    @Autowired private ComplianceCertificateRepository certificateRepo;
    @Autowired private ThresholdAuditRepository thresholdAuditRepo;
    @Autowired private CovenantRecommendationRepository recommendationRepo;
    @Autowired private CreditProposalCacheRepository cacheRepo;
    @Autowired private ObligorCpMapRepository obligorRepo;
    @Autowired private StagingValueRepository stagingRepo;
    @Autowired private IngestionStateRepository stateRepo;
    @Autowired private DataSourceRepository dataSourceRepo;
    @Autowired private DataSourceTableRepository tableRepo;

    @AfterEach
    void cleanup() {
        // FK child-to-parent so H2 never rejects a teardown delete.
        assessmentRepo.deleteAll(); // cascades cov_assessment_threshold
        scheduleRepo.deleteAll();
        appliedAuditRepo.deleteAll();
        appliedRepo.deleteAll();
        masterRepo.deleteAll();
        dispositionRepo.deleteAll();
        statusAuditRepo.deleteAll();
        attachmentRepo.deleteAll();
        certificateRepo.deleteAll();
        thresholdAuditRepo.deleteAll();
        recommendationRepo.deleteAll();
        cacheRepo.deleteAll();
        obligorRepo.deleteAll();
        stagingRepo.deleteAll();
        stateRepo.deleteAll();
        tableRepo.deleteAll();
        dataSourceRepo.deleteAll();
        opsAuditRepo.deleteAll();
    }

    @Test
    void purgesTheWholeCpGraphInFkOrderAndAuditsSuccess() {
        DataSourceTable table = seedTable();
        seedCpGraph(CP, table);
        // CP is the obligor's sole member -> obligor-grain rows are safe to delete.
        seedObligorMap(CP, OBLIGOR);
        seedStaging(table, CP, 2);      // CP-grain
        seedStaging(table, OBLIGOR, 1); // obligor-grain
        seedState(table, CP);           // CP-grain
        seedState(table, OBLIGOR);      // obligor-grain

        CpCleanupResult result = service.cleanupCp(CP);

        assertThat(result.cpId()).isEqualTo(CP);
        assertThat(result.warnings()).isEmpty();
        assertThat(result.deletedByTable())
                .containsEntry("cov_assessment_threshold", 1L)
                .containsEntry("cov_assessment", 1L)
                .containsEntry("cov_monitoring_schedule", 1L)
                .containsEntry("cov_applied_covenant_audit", 1L)
                .containsEntry("cov_covenant_status_audit", 1L)
                .containsEntry("cov_covenant_disposition", 1L)
                .containsEntry("cov_covenant_attachment", 1L)
                .containsEntry("cov_compliance_certificate", 1L)
                .containsEntry("cov_threshold_audit", 1L)
                .containsEntry("cov_applied_covenant", 1L)
                .containsEntry("cov_staging_value", 3L)   // 2 CP-grain + 1 obligor-grain
                .containsEntry("cov_ingestion_state", 2L) // 1 CP-grain + 1 obligor-grain
                .containsEntry("cov_covenant_recommendation", 1L)
                .containsEntry("cov_credit_proposal_cache", 1L)
                .containsEntry("cov_obligor_cp_map", 1L);

        // Every trace of the CP is really gone from the live schema.
        assertThat(appliedRepo.findIdsByCpId(CP)).isEmpty();
        assertThat(dispositionRepo.findByCpId(CP)).isEmpty();
        assertThat(recommendationRepo.findByCpId(CP)).isEmpty();
        assertThat(cacheRepo.findById(CP)).isEmpty();
        assertThat(obligorRepo.findByCpId(CP)).isEmpty();
        assertThat(stagingRepo.findByDataSourceTableIdAndEntityKey(table.getId(), CP)).isEmpty();
        assertThat(stagingRepo.findByDataSourceTableIdAndEntityKey(table.getId(), OBLIGOR)).isEmpty();

        // The success row committed in its own REQUIRES_NEW transaction.
        List<OpsAudit> trail = opsAuditRepo.findByTargetTypeAndTargetIdOrderByPerformedAtDesc("CP", CP);
        assertThat(trail).hasSize(1);
        assertThat(trail.get(0).getAction()).isEqualTo("CLEANUP_CP_DATA");
        assertThat(trail.get(0).getOutcome()).isEqualTo(OpsAuditOutcome.SUCCESS);
    }

    @Test
    void leavesObligorGrainStagingIntactWhenASiblingCpSharesTheObligor() {
        DataSourceTable table = seedTable();
        seedCpGraph(CP, table);
        // Both CPs map to the same obligor -> obligor-grain staging must survive.
        seedObligorMap(CP, OBLIGOR);
        seedObligorMap(SIBLING_CP, OBLIGOR);
        seedStaging(table, CP, 2);          // CP-grain (to be deleted)
        seedStaging(table, OBLIGOR, 1);     // obligor-grain (must stay)
        seedStaging(table, SIBLING_CP, 1);  // sibling CP-grain (untouched)
        seedState(table, CP);               // CP-grain (to be deleted)
        seedState(table, OBLIGOR);          // obligor-grain (must stay)

        CpCleanupResult result = service.cleanupCp(CP);

        assertThat(result.deletedByTable())
                .containsEntry("cov_staging_value", 2L)    // CP-grain only
                .containsEntry("cov_ingestion_state", 1L); // CP-grain only
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0))
                .contains(OBLIGOR)
                .contains(SIBLING_CP)
                .contains("left intact");

        // The dangerous path: obligor-grain and sibling data are all preserved.
        assertThat(stagingRepo.findByDataSourceTableIdAndEntityKey(table.getId(), OBLIGOR)).hasSize(1);
        assertThat(stagingRepo.findByDataSourceTableIdAndEntityKey(table.getId(), SIBLING_CP)).hasSize(1);
        assertThat(stateRepo.findByDataSourceTableIdAndEntityKey(table.getId(), OBLIGOR)).isPresent();
        assertThat(obligorRepo.findByCpId(SIBLING_CP)).isPresent();
        // ...while the target CP's own grain is gone.
        assertThat(stagingRepo.findByDataSourceTableIdAndEntityKey(table.getId(), CP)).isEmpty();
        assertThat(obligorRepo.findByCpId(CP)).isEmpty();
    }

    @Test
    void unknownCpProducesAnIdempotentAllZeroSummaryAndStillAudits() {
        CpCleanupResult result = service.cleanupCp("CP-DOES-NOT-EXIST");

        assertThat(result.warnings()).isEmpty();
        assertThat(result.deletedByTable().values()).containsOnly(0L);

        List<OpsAudit> trail =
                opsAuditRepo.findByTargetTypeAndTargetIdOrderByPerformedAtDesc("CP", "CP-DOES-NOT-EXIST");
        assertThat(trail).hasSize(1);
        assertThat(trail.get(0).getOutcome()).isEqualTo(OpsAuditOutcome.SUCCESS);
    }

    // ---- seed helpers -------------------------------------------------------

    private void seedCpGraph(String cpId, DataSourceTable table) {
        CovenantMaster master = masterRepo.saveAndFlush(newMaster());
        AppliedCovenant applied = appliedRepo.saveAndFlush(newApplied(cpId, master));
        String acId = applied.getId();

        Assessment assessment = new Assessment();
        assessment.setAppliedCovenant(applied);
        assessment.setScheduledAt(Instant.now());
        assessment.addComparison(new AssessmentThreshold(0, "DSCR", ">", "1.5", "2.0", FormulaType.NUMBER));
        assessmentRepo.saveAndFlush(assessment); // cascades the threshold

        MonitoringSchedule schedule = new MonitoringSchedule();
        schedule.setAppliedCovenant(applied);
        schedule.setQuartzJobKey("assessment-" + acId);
        schedule.setCronExpression(Frequency.DAILY.defaultCron());
        scheduleRepo.saveAndFlush(schedule);

        // change_type persists as lowercase via ChangeTypeConverter, satisfying ck_applied_audit_type;
        // the bulk delete filters on applied_covenant_id, so it removes the row regardless.
        appliedAuditRepo.saveAndFlush(new AppliedCovenantAudit(acId,
                AppliedCovenantAudit.ChangeType.STATUS, "ACTIVE", "PAUSED", Instant.now(), "tester"));

        CovenantDisposition disposition = new CovenantDisposition();
        disposition.setCpId(cpId);
        disposition.setAppliedCovenantId(acId);
        disposition.setStatus(CovenantDispositionStatus.BREACHED);
        disposition.setStatusSource(CovenantStatusSource.USER);
        dispositionRepo.saveAndFlush(disposition);

        statusAuditRepo.saveAndFlush(new CovenantStatusAudit(cpId, acId, null, null,
                CovenantDispositionStatus.BREACHED, null, null, CovenantStatusSource.USER, "note",
                "tester", Instant.now()));

        attachmentRepo.saveAndFlush(new CovenantAttachment(cpId, acId, "file-1", "evidence.pdf",
                "tester", Instant.now()));

        certificateRepo.saveAndFlush(new ComplianceCertificate(cpId, "cert-1", "cert.pdf",
                "tester", Instant.now()));

        thresholdAuditRepo.saveAndFlush(new ThresholdAudit(cpId, master.getId(), acId,
                ThresholdSource.USER, ThresholdAudit.Event.APPLIED, "DSCR > 1", "DSCR > 1.5",
                null, "rationale", "tester", Instant.now()));

        CovenantRecommendation recommendation = new CovenantRecommendation();
        recommendation.setCpId(cpId);
        recommendation.setCovenantMasterId(master.getId());
        recommendationRepo.saveAndFlush(recommendation);

        CreditProposalCacheEntry cache = new CreditProposalCacheEntry();
        cache.setCpId(cpId);
        cache.setAttributesJson("{}");
        cache.setFetchedAt(Instant.now());
        cacheRepo.saveAndFlush(cache);
    }

    private void seedObligorMap(String cpId, String obligorId) {
        ObligorCpMap map = new ObligorCpMap();
        map.setCpId(cpId);
        map.setObligorId(obligorId);
        obligorRepo.saveAndFlush(map);
    }

    private void seedStaging(DataSourceTable table, String entityKey, int rows) {
        for (int i = 0; i < rows; i++) {
            StagingValue value = new StagingValue();
            value.setDataSourceTable(table);
            value.setEntityKey(entityKey);
            value.setAsOfDate(LocalDate.now().minusDays(i));
            value.setItemCode("DSCR");
            value.setIngestedAt(Instant.now());
            value.setIngestBatchId("batch-1");
            stagingRepo.saveAndFlush(value);
        }
    }

    private void seedState(DataSourceTable table, String entityKey) {
        IngestionState state = new IngestionState();
        state.setDataSourceTable(table);
        state.setEntityKey(entityKey);
        stateRepo.saveAndFlush(state);
    }

    private DataSourceTable seedTable() {
        DataSource source = new DataSource();
        source.setName("Cleanup IT Source");
        source.setType(DataSourceType.REST);
        source = dataSourceRepo.saveAndFlush(source);

        DataSourceTable table = new DataSourceTable();
        table.setDataSource(source);
        table.setName("Cleanup IT Table");
        table.setTableName("cleanup_it");
        table.setEntityKeyColumn("cp_id");
        table.setAsOfColumn("as_of");
        return tableRepo.saveAndFlush(table);
    }

    private CovenantMaster newMaster() {
        CovenantMaster cm = new CovenantMaster();
        cm.setName("Cleanup IT DSCR");
        cm.setCategory(CovenantCategory.FINANCIAL);
        cm.setConditionExpression("DSCR > 1");
        cm.setBusinessSegments(List.of());
        cm.setActive(true);
        return cm;
    }

    private AppliedCovenant newApplied(String cpId, CovenantMaster master) {
        AppliedCovenant a = new AppliedCovenant();
        a.setCpId(cpId);
        a.setCovenantMaster(master);
        a.setFrequency(Frequency.DAILY);
        a.setCronExpression(Frequency.DAILY.defaultCron());
        a.setStartDate(LocalDate.now());
        a.setSnapshotJson("{}");
        return a;
    }
}
