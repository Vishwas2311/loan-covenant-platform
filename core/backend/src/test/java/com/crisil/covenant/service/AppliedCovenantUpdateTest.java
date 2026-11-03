package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.AppliedCovenantResponse;
import com.crisil.covenant.dto.UpdateAppliedCovenantRequest;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.exception.ResourceNotFoundException;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.repository.CovenantMasterRepository;
import com.crisil.covenant.entity.MonitoringSchedule;
import com.crisil.covenant.service.ScheduleService;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.repository.AppliedCovenantRepository;

@ExtendWith(MockitoExtension.class)
class AppliedCovenantUpdateTest {

    @Mock private AppliedCovenantRepository appliedRepo;
    @Mock private CovenantMasterRepository masterRepo;
    @Mock private ScheduleService scheduleService;
    @Mock private com.crisil.covenant.repository.CovenantRecommendationRepository recommendationRepo;
    @Mock private com.crisil.covenant.repository.AppliedCovenantAuditRepository auditRepo;
    @InjectMocks private AppliedCovenantService service;

    private AppliedCovenant entity;
    private MonitoringSchedule schedule;

    @BeforeEach
    void setUp() {
        entity = newApplied();
        schedule = new MonitoringSchedule();
        lenient().when(appliedRepo.findById("1")).thenReturn(Optional.of(entity));
        lenient().when(appliedRepo.saveAndFlush(any(AppliedCovenant.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(scheduleService.findByApplied("1")).thenReturn(Optional.of(schedule));
    }

    @Test
    void updateFrequencyReschedules() {
        UpdateAppliedCovenantRequest req = new UpdateAppliedCovenantRequest(
                Frequency.WEEKLY, null, null);
        AppliedCovenantResponse response = service.update("1", req);

        verify(scheduleService).reschedule(eq(schedule), eq(Frequency.WEEKLY.defaultCron()));
        assertThat(response.frequency()).isEqualTo(Frequency.WEEKLY);
        assertThat(response.cronExpression()).isEqualTo(Frequency.WEEKLY.defaultCron());
    }

    @Test
    void updateFrequencyToCustomUsesProvidedCron() {
        UpdateAppliedCovenantRequest req = new UpdateAppliedCovenantRequest(
                Frequency.CUSTOM, "0 0 3 * * ?", null);
        AppliedCovenantResponse response = service.update("1", req);
        assertThat(response.cronExpression()).isEqualTo("0 0 3 * * ?");
        verify(scheduleService).reschedule(eq(schedule), eq("0 0 3 * * ?"));
    }

    @Test
    void updateStatusToSuspendedPausesSchedule() {
        UpdateAppliedCovenantRequest req = new UpdateAppliedCovenantRequest(
                null, null, AppliedCovenantStatus.SUSPENDED);
        AppliedCovenantResponse response = service.update("1", req);
        assertThat(response.status()).isEqualTo(AppliedCovenantStatus.SUSPENDED);
        verify(scheduleService).pause(schedule);
    }

    @Test
    void updateStatusToActiveResumes() {
        entity.setStatus(AppliedCovenantStatus.SUSPENDED);
        UpdateAppliedCovenantRequest req = new UpdateAppliedCovenantRequest(
                null, null, AppliedCovenantStatus.ACTIVE);
        AppliedCovenantResponse response = service.update("1", req);
        assertThat(response.status()).isEqualTo(AppliedCovenantStatus.ACTIVE);
        verify(scheduleService).resume(schedule);
    }

    @Test
    void updateStatusToExpiredDoesNotTouchSchedule() {
        UpdateAppliedCovenantRequest req = new UpdateAppliedCovenantRequest(
                null, null, AppliedCovenantStatus.EXPIRED);
        service.update("1", req);
        verify(scheduleService, times(0)).pause(any());
        verify(scheduleService, times(0)).resume(any());
    }

    @Test
    void updateUnchangedStatusIsNoOp() {
        UpdateAppliedCovenantRequest req = new UpdateAppliedCovenantRequest(
                null, null, AppliedCovenantStatus.ACTIVE);
        service.update("1", req);
        verify(scheduleService, times(0)).pause(any());
        verify(scheduleService, times(0)).resume(any());
    }

    @Test
    void updateMissingThrowsNotFound() {
        when(appliedRepo.findById("99")).thenReturn(Optional.empty());
        UpdateAppliedCovenantRequest req = new UpdateAppliedCovenantRequest(
                Frequency.DAILY, null, null);
        assertThatThrownBy(() -> service.update("99", req))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void frequencyChangeWritesAuditRows() {
        UpdateAppliedCovenantRequest req = new UpdateAppliedCovenantRequest(
                Frequency.WEEKLY, null, null);
        service.update("1", req);
        verify(auditRepo, org.mockito.Mockito.atLeast(1))
                .save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void statusChangeWritesStatusAuditRow() {
        UpdateAppliedCovenantRequest req = new UpdateAppliedCovenantRequest(
                null, null, AppliedCovenantStatus.SUSPENDED);
        service.update("1", req);
        verify(auditRepo).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void auditForReturnsAuditRepoResults() {
        when(auditRepo.findByAppliedCovenantIdOrderByChangedDateDesc("1"))
                .thenReturn(java.util.List.of());
        assertThat(service.auditFor("1")).isEmpty();
    }

    private static AppliedCovenant newApplied() {
        CovenantMaster master = new CovenantMaster();
        setId(master, "7");
        master.setName("M");
        master.setCategory(CovenantCategory.FINANCIAL);
        master.setConditionExpression("DSCR > 1");
        master.setBusinessSegments(List.of());
        master.setActive(true);

        AppliedCovenant a = new AppliedCovenant();
        setId(a, "1");
        a.setCpId("CP-1");
        a.setCovenantMaster(master);
        a.setFrequency(Frequency.DAILY);
        a.setCronExpression(Frequency.DAILY.defaultCron());
        a.setStartDate(LocalDate.now());
        a.setSnapshotJson("{}");
        a.setStatus(AppliedCovenantStatus.ACTIVE);
        return a;
    }

    private static void setId(Object target, String id) {
        try {
            Field f = target.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(target, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
