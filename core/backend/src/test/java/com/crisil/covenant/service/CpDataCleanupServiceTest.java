package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the destructive CP cleanup orchestration: FK child-to-parent ordering, the obligor
 * shared-staging guard, the idempotent zero summary for an unknown CP, and audit-on-success /
 * audit-on-failure semantics. The real bulk-delete JPQL is exercised against H2 in
 * {@code CpDataCleanupServiceIT}; here the repositories are mocked so every branch is covered
 * deterministically.
 */
@ExtendWith(MockitoExtension.class)
class CpDataCleanupServiceTest {

    @Mock private AppliedCovenantRepository appliedRepo;
    @Mock private AssessmentRepository assessmentRepo;
    @Mock private MonitoringScheduleRepository scheduleRepo;
    @Mock private AppliedCovenantAuditRepository appliedAuditRepo;
    @Mock private CovenantStatusAuditRepository statusAuditRepo;
    @Mock private CovenantDispositionRepository dispositionRepo;
    @Mock private CovenantAttachmentRepository attachmentRepo;
    @Mock private ComplianceCertificateRepository certificateRepo;
    @Mock private ThresholdAuditRepository thresholdAuditRepo;
    @Mock private CovenantRecommendationRepository recommendationRepo;
    @Mock private CreditProposalCacheRepository cacheRepo;
    @Mock private ObligorCpMapRepository obligorRepo;
    @Mock private StagingValueRepository stagingRepo;
    @Mock private IngestionStateRepository stateRepo;
    @Mock private OpsAuditService auditService;

    @InjectMocks private CpDataCleanupService service;

    @Test
    void deletesFullGraphInFkOrderWhenObligorIsSoleMember() {
        when(appliedRepo.findIdsByCpId("CP-1")).thenReturn(List.of("ac1", "ac2"));
        when(obligorRepo.findObligorIdByCpId("CP-1")).thenReturn(Optional.of("OBL-1"));

        when(assessmentRepo.deleteThresholdsByAppliedCovenantIdIn(List.of("ac1", "ac2"))).thenReturn(7);
        when(assessmentRepo.deleteByAppliedCovenantIdIn(List.of("ac1", "ac2"))).thenReturn(3);
        when(scheduleRepo.deleteByAppliedCovenantIdIn(List.of("ac1", "ac2"))).thenReturn(2);
        when(appliedAuditRepo.deleteByAppliedCovenantIdIn(List.of("ac1", "ac2"))).thenReturn(4);
        when(statusAuditRepo.deleteByCpId("CP-1")).thenReturn(1);
        when(dispositionRepo.deleteByCpId("CP-1")).thenReturn(2);
        when(attachmentRepo.deleteByCpId("CP-1")).thenReturn(1);
        when(certificateRepo.deleteByCpId("CP-1")).thenReturn(1);
        when(thresholdAuditRepo.deleteByCpId("CP-1")).thenReturn(1);
        when(appliedRepo.deleteByCpId("CP-1")).thenReturn(2);
        when(stagingRepo.deleteByEntityKey("CP-1")).thenReturn(5L);
        when(stateRepo.deleteByEntityKey("CP-1")).thenReturn(2L);
        // Sole member of the obligor -> obligor-grain rows are safe to delete too.
        when(obligorRepo.findCpIdsByObligorId("OBL-1")).thenReturn(List.of("CP-1"));
        when(stagingRepo.deleteByEntityKey("OBL-1")).thenReturn(8L);
        when(stateRepo.deleteByEntityKey("OBL-1")).thenReturn(1L);
        when(recommendationRepo.deleteByCpId("CP-1")).thenReturn(2);
        when(cacheRepo.deleteByCpId("CP-1")).thenReturn(1);
        when(obligorRepo.deleteByCpId("CP-1")).thenReturn(1);

        CpCleanupResult result = service.cleanupCp("CP-1");

        assertThat(result.cpId()).isEqualTo("CP-1");
        assertThat(result.warnings()).isEmpty();
        assertThat(result.deletedByTable()).containsExactly(
                entry("cov_assessment_threshold", 7L),
                entry("cov_assessment", 3L),
                entry("cov_monitoring_schedule", 2L),
                entry("cov_applied_covenant_audit", 4L),
                entry("cov_covenant_status_audit", 1L),
                entry("cov_covenant_disposition", 2L),
                entry("cov_covenant_attachment", 1L),
                entry("cov_compliance_certificate", 1L),
                entry("cov_threshold_audit", 1L),
                entry("cov_applied_covenant", 2L),
                entry("cov_staging_value", 13L),   // 5 CP-grain + 8 obligor-grain
                entry("cov_ingestion_state", 3L),   // 2 CP-grain + 1 obligor-grain
                entry("cov_covenant_recommendation", 2L),
                entry("cov_credit_proposal_cache", 1L),
                entry("cov_obligor_cp_map", 1L));
        verify(auditService).recordSuccess(eq("CLEANUP_CP_DATA"), eq("CP"), eq("CP-1"),
                eq("confirmCpId=CP-1"), anyString());
        verify(auditService, never()).recordFailure(anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    void skipsObligorGrainStagingAndWarnsWhenObligorShared() {
        when(appliedRepo.findIdsByCpId("CP-1")).thenReturn(List.of("ac1"));
        when(obligorRepo.findObligorIdByCpId("CP-1")).thenReturn(Optional.of("OBL-1"));
        when(assessmentRepo.deleteThresholdsByAppliedCovenantIdIn(List.of("ac1"))).thenReturn(1);
        when(assessmentRepo.deleteByAppliedCovenantIdIn(List.of("ac1"))).thenReturn(1);
        when(scheduleRepo.deleteByAppliedCovenantIdIn(List.of("ac1"))).thenReturn(1);
        when(appliedAuditRepo.deleteByAppliedCovenantIdIn(List.of("ac1"))).thenReturn(0);
        when(stagingRepo.deleteByEntityKey("CP-1")).thenReturn(4L);
        when(stateRepo.deleteByEntityKey("CP-1")).thenReturn(1L);
        // A sibling CP still maps to the obligor -> obligor-grain staging must be left intact.
        when(obligorRepo.findCpIdsByObligorId("OBL-1")).thenReturn(List.of("CP-1", "CP-2"));
        when(appliedRepo.deleteByCpId("CP-1")).thenReturn(1);

        CpCleanupResult result = service.cleanupCp("CP-1");

        assertThat(result.deletedByTable())
                .containsEntry("cov_staging_value", 4L)      // CP-grain only
                .containsEntry("cov_ingestion_state", 1L);
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0))
                .contains("OBL-1")
                .contains("CP-2")
                .contains("left intact");
        // The guard must never touch the obligor-grain rows.
        verify(stagingRepo, never()).deleteByEntityKey("OBL-1");
        verify(stateRepo, never()).deleteByEntityKey("OBL-1");
        verify(auditService).recordSuccess(eq("CLEANUP_CP_DATA"), eq("CP"), eq("CP-1"),
                anyString(), anyString());
    }

    @Test
    void deletesOnlyCpGrainWhenNoObligorMapping() {
        when(appliedRepo.findIdsByCpId("CP-1")).thenReturn(List.of("ac1"));
        when(obligorRepo.findObligorIdByCpId("CP-1")).thenReturn(Optional.empty());
        when(assessmentRepo.deleteThresholdsByAppliedCovenantIdIn(List.of("ac1"))).thenReturn(0);
        when(assessmentRepo.deleteByAppliedCovenantIdIn(List.of("ac1"))).thenReturn(0);
        when(scheduleRepo.deleteByAppliedCovenantIdIn(List.of("ac1"))).thenReturn(0);
        when(appliedAuditRepo.deleteByAppliedCovenantIdIn(List.of("ac1"))).thenReturn(0);
        when(stagingRepo.deleteByEntityKey("CP-1")).thenReturn(6L);
        when(stateRepo.deleteByEntityKey("CP-1")).thenReturn(2L);

        CpCleanupResult result = service.cleanupCp("CP-1");

        assertThat(result.warnings()).isEmpty();
        assertThat(result.deletedByTable())
                .containsEntry("cov_staging_value", 6L)
                .containsEntry("cov_ingestion_state", 2L);
        // With no obligor there is no reverse fan-out lookup and no obligor-grain delete.
        verify(obligorRepo, never()).findCpIdsByObligorId(anyString());
        verify(stagingRepo, never()).deleteByEntityKey(eq("OBL-1"));
    }

    @Test
    void returnsIdempotentZeroSummaryForUnknownCp() {
        when(appliedRepo.findIdsByCpId("CP-GONE")).thenReturn(List.of());
        when(obligorRepo.findObligorIdByCpId("CP-GONE")).thenReturn(Optional.empty());

        CpCleanupResult result = service.cleanupCp("CP-GONE");

        assertThat(result.cpId()).isEqualTo("CP-GONE");
        assertThat(result.warnings()).isEmpty();
        assertThat(result.deletedByTable().values()).containsOnly(0L);
        assertThat(result.deletedByTable()).containsKeys(
                "cov_assessment_threshold", "cov_assessment", "cov_monitoring_schedule",
                "cov_applied_covenant_audit", "cov_applied_covenant", "cov_staging_value",
                "cov_ingestion_state", "cov_obligor_cp_map");
        // The empty applied-covenant list short-circuits the child deletes entirely.
        verify(assessmentRepo, never()).deleteThresholdsByAppliedCovenantIdIn(org.mockito.ArgumentMatchers.anyList());
        verify(assessmentRepo, never()).deleteByAppliedCovenantIdIn(org.mockito.ArgumentMatchers.anyList());
        verify(auditService).recordSuccess(eq("CLEANUP_CP_DATA"), eq("CP"), eq("CP-GONE"),
                anyString(), anyString());
    }

    @Test
    void auditsFailureAndRethrowsWhenADeleteFails() {
        when(appliedRepo.findIdsByCpId("CP-1")).thenReturn(List.of("ac1"));
        when(obligorRepo.findObligorIdByCpId("CP-1")).thenReturn(Optional.empty());
        RuntimeException boom = new RuntimeException("db exploded");
        when(assessmentRepo.deleteThresholdsByAppliedCovenantIdIn(List.of("ac1"))).thenThrow(boom);

        assertThatThrownBy(() -> service.cleanupCp("CP-1")).isSameAs(boom);

        verify(auditService).recordFailure(eq("CLEANUP_CP_DATA"), eq("CP"), eq("CP-1"),
                eq("confirmCpId=CP-1"), eq("db exploded"));
        verify(auditService, never()).recordSuccess(anyString(), anyString(), anyString(),
                anyString(), anyString());
    }
}
