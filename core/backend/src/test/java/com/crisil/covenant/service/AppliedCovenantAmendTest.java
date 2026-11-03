package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.crisil.covenant.dto.UpdateAppliedCovenantRequest;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.entity.ThresholdAudit;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.enums.ThresholdSource;
import com.crisil.covenant.repository.AppliedCovenantAuditRepository;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.CovenantMasterRepository;
import com.crisil.covenant.repository.CovenantRecommendationRepository;
import com.crisil.covenant.repository.ThresholdAuditRepository;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** A3: changing one CP's rule for a covenant after it was applied, and the definition-history row. */
@ExtendWith(MockitoExtension.class)
class AppliedCovenantAmendTest {

    @Mock private AppliedCovenantRepository appliedRepo;
    @Mock private CovenantMasterRepository masterRepo;
    @Mock private ScheduleService scheduleService;
    @Mock private CovenantRecommendationRepository recommendationRepo;
    @Mock private AppliedCovenantAuditRepository auditRepo;
    @Mock private ThresholdAuditRepository thresholdAuditRepo;
    @Mock private CovenantConditionValidator conditionValidator;
    @InjectMocks private AppliedCovenantService service;

    private AppliedCovenant entity;

    @BeforeEach
    void setUp() throws Exception {
        CovenantMaster master = new CovenantMaster();
        setId(master, "7");
        master.setName("Minimum DSCR");
        master.setCategory(CovenantCategory.FINANCIAL);
        master.setConditionExpression("DSCR >= 1.5");
        master.setBusinessSegments(List.of());
        master.setActive(true);
        entity = new AppliedCovenant();
        setId(entity, "1");
        entity.setCpId("CP-1");
        entity.setCovenantMaster(master);
        entity.setFrequency(Frequency.QUARTERLY);
        entity.setCronExpression(Frequency.QUARTERLY.defaultCron());
        entity.setStartDate(LocalDate.now());
        entity.setSnapshotJson("{}");
        entity.setStatus(AppliedCovenantStatus.ACTIVE);
        lenient().when(appliedRepo.findById("1")).thenReturn(Optional.of(entity));
        lenient().when(appliedRepo.saveAndFlush(any(AppliedCovenant.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static UpdateAppliedCovenantRequest rule(String expression, String reason) {
        return new UpdateAppliedCovenantRequest(null, null, null, expression, reason);
    }

    @Test
    void newRuleIsValidatedStoredAndLogged() {
        var response = service.update("1", rule(" DSCR >= 1.3 ", "Amendment 2 signed"));

        verify(conditionValidator).validateOrThrow(" DSCR >= 1.3 ");
        assertThat(response.conditionOverride()).isEqualTo("DSCR >= 1.3");
        assertThat(response.effectiveCondition()).isEqualTo("DSCR >= 1.3");
        assertThat(response.thresholdSource()).isEqualTo(ThresholdSource.USER);
        ArgumentCaptor<ThresholdAudit> audit = ArgumentCaptor.forClass(ThresholdAudit.class);
        verify(thresholdAuditRepo).save(audit.capture());
        assertThat(audit.getValue().getEvent()).isEqualTo(ThresholdAudit.Event.AMENDED);
        assertThat(audit.getValue().getOldExpression()).isEqualTo("DSCR >= 1.5");
        assertThat(audit.getValue().getNewExpression()).isEqualTo("DSCR >= 1.3");
        assertThat(audit.getValue().getRationale()).isEqualTo("Amendment 2 signed");
    }

    @Test
    void blankGoesBackToTheLibraryRule() {
        entity.setConditionExpression("DSCR >= 1.3");
        entity.setThresholdSource(ThresholdSource.USER);

        var response = service.update("1", rule("", " "));

        assertThat(response.conditionOverride()).isNull();
        assertThat(response.effectiveCondition()).isEqualTo("DSCR >= 1.5");
        assertThat(response.thresholdSource()).isEqualTo(ThresholdSource.MASTER);
        ArgumentCaptor<ThresholdAudit> audit = ArgumentCaptor.forClass(ThresholdAudit.class);
        verify(thresholdAuditRepo).save(audit.capture());
        assertThat(audit.getValue().getRationale()).isNull();
        assertThat(audit.getValue().getNewExpression()).isEqualTo("DSCR >= 1.5");
    }

    @Test
    void sameRuleChangesNothing() {
        service.update("1", rule("", null));
        service.update("1", new UpdateAppliedCovenantRequest(null, null, null));
        verify(thresholdAuditRepo, never()).save(any());
    }

    private static void setId(Object target, String id) throws Exception {
        Field f = target.getClass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(target, id);
    }
}
