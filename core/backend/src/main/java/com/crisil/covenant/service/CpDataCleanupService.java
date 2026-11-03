package com.crisil.covenant.service;

import com.crisil.covenant.dto.ops.CpCleanupResult;
import com.crisil.covenant.repository.AppliedCovenantAuditRepository;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.AssessmentRepository;
import com.crisil.covenant.repository.ComplianceCertificateRepository;
import com.crisil.covenant.repository.CovenantAttachmentRepository;
import com.crisil.covenant.repository.CovenantDispositionRepository;
import com.crisil.covenant.repository.CovenantRecommendationRepository;
import com.crisil.covenant.repository.CovenantStatusAuditRepository;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import com.crisil.covenant.repository.IngestionStateRepository;
import com.crisil.covenant.repository.MonitoringScheduleRepository;
import com.crisil.covenant.repository.ObligorCpMapRepository;
import com.crisil.covenant.repository.StagingValueRepository;
import com.crisil.covenant.repository.ThresholdAuditRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Destructive production-support cleanup: purge every trace of one CP in a single atomic transaction,
 * replacing the fragile hand-ordered SQL a support engineer would otherwise run against the console.
 *
 * <p>The deletes run <strong>FK child-to-parent</strong> so the database never rejects one: the
 * assessment sub-tree ({@code cov_assessment_threshold} then {@code cov_assessment}), the other
 * applied-covenant children ({@code cov_monitoring_schedule}, {@code cov_applied_covenant_audit}), the
 * CP-keyed satellites (dispositions, status audit, attachments, compliance certificates, threshold
 * audit), then the {@code cov_applied_covenant} parent, and finally the staging store, freshness-gate
 * state, recommendations, CP cache and obligor mapping. Each step's row count is recorded (in order) in
 * the result map.
 *
 * <p><strong>Obligor-shared-staging guard.</strong> Staging and ingestion-state rows are keyed by an
 * entity key that is the CP id for CP-grain tables but the <em>obligor</em> id for obligor-grain
 * tables. CP-grain rows are always safe to delete; obligor-grain rows are only deleted when this CP is
 * the obligor's <em>sole</em> member. When sibling CPs still map to the same obligor the obligor-grain
 * rows are left intact and a warning is returned — this is the single most dangerous data-loss path.
 *
 * <p>The whole method is one {@link Transactional} unit, so any failure rolls the entire purge back; the
 * audit row is written through {@link OpsAuditService} (its own {@code REQUIRES_NEW} transaction) so the
 * outcome — success or failure — survives that rollback. A non-existent CP is not an error: every delete
 * simply returns zero and an idempotent all-zero summary is produced (and still audited).
 */
@Service
public class CpDataCleanupService {

    private static final String ACTION_CLEANUP = "CLEANUP_CP_DATA";
    private static final String TARGET_CP = "CP";

    private final AppliedCovenantRepository appliedRepo;
    private final AssessmentRepository assessmentRepo;
    private final MonitoringScheduleRepository scheduleRepo;
    private final AppliedCovenantAuditRepository appliedAuditRepo;
    private final CovenantStatusAuditRepository statusAuditRepo;
    private final CovenantDispositionRepository dispositionRepo;
    private final CovenantAttachmentRepository attachmentRepo;
    private final ComplianceCertificateRepository certificateRepo;
    private final ThresholdAuditRepository thresholdAuditRepo;
    private final CovenantRecommendationRepository recommendationRepo;
    private final CreditProposalCacheRepository cacheRepo;
    private final ObligorCpMapRepository obligorRepo;
    private final StagingValueRepository stagingRepo;
    private final IngestionStateRepository stateRepo;
    private final OpsAuditService auditService;

    public CpDataCleanupService(AppliedCovenantRepository appliedRepo,
                                AssessmentRepository assessmentRepo,
                                MonitoringScheduleRepository scheduleRepo,
                                AppliedCovenantAuditRepository appliedAuditRepo,
                                CovenantStatusAuditRepository statusAuditRepo,
                                CovenantDispositionRepository dispositionRepo,
                                CovenantAttachmentRepository attachmentRepo,
                                ComplianceCertificateRepository certificateRepo,
                                ThresholdAuditRepository thresholdAuditRepo,
                                CovenantRecommendationRepository recommendationRepo,
                                CreditProposalCacheRepository cacheRepo,
                                ObligorCpMapRepository obligorRepo,
                                StagingValueRepository stagingRepo,
                                IngestionStateRepository stateRepo,
                                OpsAuditService auditService) {
        this.appliedRepo = appliedRepo;
        this.assessmentRepo = assessmentRepo;
        this.scheduleRepo = scheduleRepo;
        this.appliedAuditRepo = appliedAuditRepo;
        this.statusAuditRepo = statusAuditRepo;
        this.dispositionRepo = dispositionRepo;
        this.attachmentRepo = attachmentRepo;
        this.certificateRepo = certificateRepo;
        this.thresholdAuditRepo = thresholdAuditRepo;
        this.recommendationRepo = recommendationRepo;
        this.cacheRepo = cacheRepo;
        this.obligorRepo = obligorRepo;
        this.stagingRepo = stagingRepo;
        this.stateRepo = stateRepo;
        this.auditService = auditService;
    }

    /** Atomically delete all data for a CP, FK child-to-parent, guarding obligor-shared staging. */
    @Transactional
    public CpCleanupResult cleanupCp(String cpId) {
        List<String> warnings = new ArrayList<>();
        Map<String, Long> deleted = new LinkedHashMap<>();
        try {
            List<String> appliedCovenantIds = appliedRepo.findIdsByCpId(cpId);
            // Resolve the obligor BEFORE its mapping row is deleted, for the shared-staging guard.
            Optional<String> obligorId = obligorRepo.findObligorIdByCpId(cpId);

            deleteAppliedCovenantChildren(appliedCovenantIds, deleted);
            deleteCpKeyedSatellites(cpId, deleted);
            deleted.put("cov_applied_covenant", (long) appliedRepo.deleteByCpId(cpId));
            deleteStagingAndState(cpId, obligorId.orElse(null), deleted, warnings);
            deleted.put("cov_covenant_recommendation", (long) recommendationRepo.deleteByCpId(cpId));
            deleted.put("cov_credit_proposal_cache", (long) cacheRepo.deleteByCpId(cpId));
            deleted.put("cov_obligor_cp_map", (long) obligorRepo.deleteByCpId(cpId));

            long total = deleted.values().stream().mapToLong(Long::longValue).sum();
            auditService.recordSuccess(ACTION_CLEANUP, TARGET_CP, cpId, "confirmCpId=" + cpId,
                    "totalDeleted=" + total + ", byTable=" + deleted + ", warnings=" + warnings.size());
            return new CpCleanupResult(cpId, deleted, warnings);
        } catch (RuntimeException ex) {
            auditService.recordFailure(ACTION_CLEANUP, TARGET_CP, cpId, "confirmCpId=" + cpId,
                    ex.getMessage());
            throw ex;
        }
    }

    /** Assessment sub-tree (thresholds before assessments) then the other applied-covenant children. */
    private void deleteAppliedCovenantChildren(List<String> appliedCovenantIds, Map<String, Long> out) {
        if (appliedCovenantIds.isEmpty()) {
            out.put("cov_assessment_threshold", 0L);
            out.put("cov_assessment", 0L);
            out.put("cov_monitoring_schedule", 0L);
            out.put("cov_applied_covenant_audit", 0L);
            return;
        }
        out.put("cov_assessment_threshold",
                (long) assessmentRepo.deleteThresholdsByAppliedCovenantIdIn(appliedCovenantIds));
        out.put("cov_assessment",
                (long) assessmentRepo.deleteByAppliedCovenantIdIn(appliedCovenantIds));
        out.put("cov_monitoring_schedule",
                (long) scheduleRepo.deleteByAppliedCovenantIdIn(appliedCovenantIds));
        out.put("cov_applied_covenant_audit",
                (long) appliedAuditRepo.deleteByAppliedCovenantIdIn(appliedCovenantIds));
    }

    /** CP-keyed rows with no FK to the applied covenant — safe to delete in any order before the parent. */
    private void deleteCpKeyedSatellites(String cpId, Map<String, Long> out) {
        out.put("cov_covenant_status_audit", (long) statusAuditRepo.deleteByCpId(cpId));
        out.put("cov_covenant_disposition", (long) dispositionRepo.deleteByCpId(cpId));
        out.put("cov_covenant_attachment", (long) attachmentRepo.deleteByCpId(cpId));
        out.put("cov_compliance_certificate", (long) certificateRepo.deleteByCpId(cpId));
        out.put("cov_threshold_audit", (long) thresholdAuditRepo.deleteByCpId(cpId));
    }

    /**
     * Delete staging + freshness-gate state. CP-grain rows (entity key == cpId) always go; obligor-grain
     * rows (entity key == obligorId) go only when this CP is the obligor's sole member, else a warning.
     */
    private void deleteStagingAndState(String cpId, String obligorId, Map<String, Long> out,
                                       List<String> warnings) {
        long stagingDeleted = stagingRepo.deleteByEntityKey(cpId);
        long stateDeleted = stateRepo.deleteByEntityKey(cpId);
        if (obligorId != null) {
            List<String> siblings = obligorRepo.findCpIdsByObligorId(obligorId).stream()
                    .filter(id -> !id.equals(cpId))
                    .toList();
            if (siblings.isEmpty()) {
                stagingDeleted += stagingRepo.deleteByEntityKey(obligorId);
                stateDeleted += stateRepo.deleteByEntityKey(obligorId);
            } else {
                warnings.add("Obligor '" + obligorId + "' is shared by other CPs " + siblings
                        + " — obligor-grain staging and ingestion-state left intact.");
            }
        }
        out.put("cov_staging_value", stagingDeleted);
        out.put("cov_ingestion_state", stateDeleted);
    }
}
