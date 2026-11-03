package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.repository.AppliedCovenantAuditRepository;
import com.crisil.covenant.dto.ApplyCovenantRequest;
import com.crisil.covenant.dto.BulkApplyCovenantsRequest;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.repository.CovenantMasterRepository;
import com.crisil.covenant.entity.CovenantRecommendation;
import com.crisil.covenant.repository.CovenantRecommendationRepository;
import com.crisil.covenant.enums.RecommendationStatus;
import com.crisil.covenant.entity.MonitoringSchedule;
import com.crisil.covenant.service.ScheduleService;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.repository.AppliedCovenantRepository;

@ExtendWith(MockitoExtension.class)
class AppliedCovenantReconciliationTest {

    @Mock private AppliedCovenantRepository appliedRepo;
    @Mock private CovenantMasterRepository masterRepo;
    @Mock private ScheduleService scheduleService;
    @Mock private CovenantRecommendationRepository recommendationRepo;
    @Mock private AppliedCovenantAuditRepository auditRepo;
    @InjectMocks private AppliedCovenantService service;

    @BeforeEach
    void seed() {
        lenient().when(scheduleService.createSchedule(any())).thenReturn(new MonitoringSchedule());
        lenient().when(appliedRepo.saveAndFlush(any(AppliedCovenant.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void appliedCovenantWithExistingSuggestionGetsMarkedAccepted() {
        CovenantMaster master = newMaster("1");
        when(masterRepo.findById("1")).thenReturn(Optional.of(master));
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(false);
        CovenantRecommendation suggested = new CovenantRecommendation();
        suggested.setCpId("CP-1");
        suggested.setCovenantMasterId("1");
        suggested.setStatus(RecommendationStatus.SUGGESTED);
        when(recommendationRepo.findByCpId("CP-1")).thenReturn(List.of(suggested));

        service.applyBulk("CP-1", new BulkApplyCovenantsRequest(List.of(
                new ApplyCovenantRequest("1", Frequency.DAILY, null, null))));

        ArgumentCaptor<CovenantRecommendation> cap =
                ArgumentCaptor.forClass(CovenantRecommendation.class);
        verify(recommendationRepo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(RecommendationStatus.ACCEPTED);
    }

    @Test
    void unselectedSuggestedRecommendationGetsRejected() {
        CovenantMaster m1 = newMaster("1");
        when(masterRepo.findById("1")).thenReturn(Optional.of(m1));
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(false);
        // Suggested rows: 1 (applied) + 2 (unselected) — 2 should flip to REJECTED.
        CovenantRecommendation s1 = newRec("1", RecommendationStatus.SUGGESTED);
        CovenantRecommendation s2 = newRec("2", RecommendationStatus.SUGGESTED);
        when(recommendationRepo.findByCpId("CP-1")).thenReturn(List.of(s1, s2));

        service.applyBulk("CP-1", new BulkApplyCovenantsRequest(List.of(
                new ApplyCovenantRequest("1", Frequency.DAILY, null, null))));

        verify(recommendationRepo, times(2)).save(any());
        assertThat(s2.getStatus()).isEqualTo(RecommendationStatus.REJECTED);
    }

    @Test
    void appliedCovenantWithoutPriorRecommendationLoggedAsManualAdd() {
        CovenantMaster master = newMaster("7");
        when(masterRepo.findById("7")).thenReturn(Optional.of(master));
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-X", "7")).thenReturn(false);
        when(recommendationRepo.findByCpId("CP-X")).thenReturn(List.of());

        service.applyBulk("CP-X", new BulkApplyCovenantsRequest(List.of(
                new ApplyCovenantRequest("7", Frequency.DAILY, null, null))));

        ArgumentCaptor<CovenantRecommendation> cap =
                ArgumentCaptor.forClass(CovenantRecommendation.class);
        verify(recommendationRepo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(RecommendationStatus.MANUAL_ADD);
    }

    private CovenantRecommendation newRec(String covId, RecommendationStatus status) {
        CovenantRecommendation r = new CovenantRecommendation();
        r.setCpId("CP-1");
        r.setCovenantMasterId(covId);
        r.setStatus(status);
        return r;
    }

    private static CovenantMaster newMaster(String id) {
        CovenantMaster m = new CovenantMaster();
        try {
            Field f = CovenantMaster.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(m, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        m.setName("M-" + id);
        m.setCategory(CovenantCategory.FINANCIAL);
        m.setConditionExpression("DSCR > 1");
        m.setBusinessSegments(List.of());
        m.setActive(true);
        return m;
    }
}
