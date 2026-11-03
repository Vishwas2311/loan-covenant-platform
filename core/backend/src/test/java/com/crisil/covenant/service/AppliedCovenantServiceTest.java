package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.entity.ThresholdAudit;
import com.crisil.covenant.dto.AppliedCovenantResponse;
import com.crisil.covenant.dto.ApplyCovenantRequest;
import com.crisil.covenant.dto.BulkApplyCovenantsRequest;
import com.crisil.covenant.dto.DefinitionChangeRow;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.exception.ResourceNotFoundException;
import com.crisil.covenant.enums.CovenantClassification;
import com.crisil.covenant.enums.CovenantConditionType;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.repository.CovenantMasterRepository;
import com.crisil.covenant.entity.MonitoringSchedule;
import com.crisil.covenant.service.ScheduleService;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.enums.ThresholdSource;
import com.crisil.covenant.repository.AppliedCovenantRepository;

@ExtendWith(MockitoExtension.class)
class AppliedCovenantServiceTest {

    @Mock private AppliedCovenantRepository appliedRepo;
    @Mock private CovenantMasterRepository masterRepo;
    @Mock private ScheduleService scheduleService;
    @Mock private com.crisil.covenant.repository.CovenantRecommendationRepository recommendationRepo;
    @Mock private com.crisil.covenant.repository.AppliedCovenantAuditRepository auditRepo;
    @Mock private com.crisil.covenant.repository.ThresholdAuditRepository thresholdAuditRepo;
    @Mock private CovenantConditionValidator conditionValidator;

    @InjectMocks private AppliedCovenantService service;

    @BeforeEach
    void stubSchedule() {
        Mockito.lenient().when(scheduleService.createSchedule(any())).thenReturn(new MonitoringSchedule());
    }

    private static ApplyCovenantRequest apply(String id, Frequency f, String cron) {
        return new ApplyCovenantRequest(id, f, cron, null);
    }

    @Test
    void appliesSingleCovenantWithStandardFrequency() {
        CovenantMaster master = newMaster("1", true);
        when(masterRepo.findById("1")).thenReturn(Optional.of(master));
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(false);
        when(appliedRepo.saveAndFlush(any(AppliedCovenant.class))).thenAnswer(inv -> {
            AppliedCovenant a = inv.getArgument(0);
            Field f = AppliedCovenant.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(a, "100");
            return a;
        });

        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(apply("1", Frequency.DAILY, null)));
        List<AppliedCovenantResponse> result = service.applyBulk("CP-1", req);

        assertThat(result).hasSize(1);
        AppliedCovenantResponse first = result.get(0);
        assertThat(first.frequency()).isEqualTo(Frequency.DAILY);
        assertThat(first.cronExpression()).isEqualTo(Frequency.DAILY.defaultCron());
        assertThat(first.status()).isEqualTo(AppliedCovenantStatus.ACTIVE);
        assertThat(first.conditionOverride()).isNull();
        assertThat(first.effectiveCondition()).isEqualTo("DSCR > 1");
    }

    @Test
    void appliesCustomCron() {
        CovenantMaster master = newMaster("1", true);
        when(masterRepo.findById("1")).thenReturn(Optional.of(master));
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(false);
        when(appliedRepo.saveAndFlush(any(AppliedCovenant.class))).thenAnswer(inv -> inv.getArgument(0));

        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(
                apply("1", Frequency.CUSTOM, "0 0 3 * * ?")));
        List<AppliedCovenantResponse> result = service.applyBulk("CP-1", req);

        assertThat(result.get(0).cronExpression()).isEqualTo("0 0 3 * * ?");
    }

    @Test
    void appliesValidThresholdOverride() {
        CovenantMaster master = newMaster("1", true);
        when(masterRepo.findById("1")).thenReturn(Optional.of(master));
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(false);
        when(appliedRepo.saveAndFlush(any(AppliedCovenant.class))).thenAnswer(inv -> inv.getArgument(0));

        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(
                new ApplyCovenantRequest("1", Frequency.DAILY, null, "DSCR > 1.7")));
        AppliedCovenantResponse r = service.applyBulk("CP-1", req).get(0);

        assertThat(r.conditionOverride()).isEqualTo("DSCR > 1.7");
        assertThat(r.effectiveCondition()).isEqualTo("DSCR > 1.7");
        verify(conditionValidator).validateOrThrow("DSCR > 1.7");
    }

    @Test
    void rejectsInvalidThresholdOverride() {
        CovenantMaster master = newMaster("1", true);
        when(masterRepo.findById("1")).thenReturn(Optional.of(master));
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(false);
        doThrow(new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RULE_EXPRESSION", "bad"))
                .when(conditionValidator).validateOrThrow("DSCR >");

        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(
                new ApplyCovenantRequest("1", Frequency.DAILY, null, "DSCR >")));
        assertThatThrownBy(() -> service.applyBulk("CP-1", req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("bad");
        // AC3: no applied covenant, schedule or audit row is created for an invalid definition.
        verify(appliedRepo, never()).saveAndFlush(any());
        verify(scheduleService, never()).createSchedule(any());
        verify(thresholdAuditRepo, never()).save(any());
    }

    /**
     * US-CM-25 AC2: the per-CP definition may reference measures the master never used. The
     * measure-equality guard that rejected this with {@code INVALID_THRESHOLD_OVERRIDE} is gone.
     */
    @Test
    void acceptsDefinitionThatChangesTheMeasuresUsed() {
        CovenantMaster master = newMaster("1", true);
        when(masterRepo.findById("1")).thenReturn(Optional.of(master));
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(false);
        stubSavedWithId("100");

        String perCp = "LATEST(ICR) >= 2.0 AND LATEST(DSCR) >= 1.3";
        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(
                new ApplyCovenantRequest("1", Frequency.DAILY, null, perCp)));
        AppliedCovenantResponse r = service.applyBulk("CP-1", req).get(0);

        assertThat(r.conditionOverride()).isEqualTo(perCp);
        assertThat(r.effectiveCondition()).isEqualTo(perCp);
        assertThat(r.thresholdSource()).isEqualTo(ThresholdSource.USER);
        // The master itself is untouched.
        assertThat(master.getConditionExpression()).isEqualTo("DSCR > 1");

        ArgumentCaptor<ThresholdAudit> captor = ArgumentCaptor.forClass(ThresholdAudit.class);
        verify(thresholdAuditRepo).save(captor.capture());
        assertThat(captor.getValue().getOldExpression()).isEqualTo("DSCR > 1");
        assertThat(captor.getValue().getNewExpression()).isEqualTo(perCp);
    }

    @Test
    void trimsTheCommittedDefinition() {
        CovenantMaster master = newMaster("1", true);
        when(masterRepo.findById("1")).thenReturn(Optional.of(master));
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(false);
        when(appliedRepo.saveAndFlush(any(AppliedCovenant.class))).thenAnswer(inv -> inv.getArgument(0));

        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(
                new ApplyCovenantRequest("1", Frequency.DAILY, null, "  DSCR > 1.7  ")));
        AppliedCovenantResponse r = service.applyBulk("CP-1", req).get(0);

        assertThat(r.conditionOverride()).isEqualTo("DSCR > 1.7");
    }

    /** AC4: a blank definition is not an override — it means "use the master's". */
    @Test
    void blankDefinitionIsNotAnOverride() {
        CovenantMaster master = newMaster("1", true);
        when(masterRepo.findById("1")).thenReturn(Optional.of(master));
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(false);
        when(appliedRepo.saveAndFlush(any(AppliedCovenant.class))).thenAnswer(inv -> inv.getArgument(0));

        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(
                new ApplyCovenantRequest("1", Frequency.DAILY, null, "   ")));
        AppliedCovenantResponse r = service.applyBulk("CP-1", req).get(0);

        assertThat(r.conditionOverride()).isNull();
        assertThat(r.effectiveCondition()).isEqualTo("DSCR > 1");
        assertThat(r.thresholdSource()).isEqualTo(ThresholdSource.MASTER);
        verify(thresholdAuditRepo, never()).save(any());
    }

    @Test
    void rejectsCustomFrequencyWithoutCron() {
        CovenantMaster master = newMaster("1", true);
        when(masterRepo.findById("1")).thenReturn(Optional.of(master));
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(false);

        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(apply("1", Frequency.CUSTOM, null)));
        assertThatThrownBy(() -> service.applyBulk("CP-1", req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Custom frequency");
    }

    @Test
    void rejectsCustomFrequencyWithInvalidCron() {
        CovenantMaster master = newMaster("1", true);
        when(masterRepo.findById("1")).thenReturn(Optional.of(master));
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(false);

        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(apply("1", Frequency.CUSTOM, "not-a-cron")));
        assertThatThrownBy(() -> service.applyBulk("CP-1", req)).isInstanceOf(ApiException.class);
    }

    @Test
    void skipsAlreadyAppliedCovenantInBulk() {
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(true);
        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(apply("1", Frequency.DAILY, null)));

        List<AppliedCovenantResponse> out = service.applyBulk("CP-1", req);

        assertThat(out).isEmpty(); // already applied → skipped (idempotent), not re-applied
        verify(appliedRepo, never()).saveAndFlush(any());
    }

    @Test
    void appliesNewCovenantsAndSkipsAlreadyApplied() {
        CovenantMaster master = newMaster("2", true);
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(true);  // already applied
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "2")).thenReturn(false); // new
        when(masterRepo.findById("2")).thenReturn(Optional.of(master));
        when(appliedRepo.saveAndFlush(any(AppliedCovenant.class))).thenAnswer(inv -> inv.getArgument(0));

        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(
                apply("1", Frequency.DAILY, null), apply("2", Frequency.DAILY, null)));
        List<AppliedCovenantResponse> out = service.applyBulk("CP-1", req);

        assertThat(out).hasSize(1); // only the new covenant is applied; the duplicate is skipped
        assertThat(out.get(0).covenantMasterId()).isEqualTo("2");
    }

    @Test
    void rejectsMissingMaster() {
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "99")).thenReturn(false);
        when(masterRepo.findById("99")).thenReturn(Optional.empty());
        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(apply("99", Frequency.DAILY, null)));
        assertThatThrownBy(() -> service.applyBulk("CP-1", req))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rejectsInactiveMaster() {
        CovenantMaster master = newMaster("1", false);
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(false);
        when(masterRepo.findById("1")).thenReturn(Optional.of(master));
        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(apply("1", Frequency.DAILY, null)));
        assertThatThrownBy(() -> service.applyBulk("CP-1", req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("inactive covenant");
    }

    @Test
    void listForCpMapsAll() {
        AppliedCovenant a = newApplied("1", newMaster("7", true));
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of(a));
        List<AppliedCovenantResponse> out = service.listForCp("CP-1");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).covenantName()).isEqualTo("Master 7");
    }

    @Test
    void allStandardFrequenciesResolveToDefaultCron() {
        for (Frequency f : List.of(Frequency.DAILY, Frequency.WEEKLY, Frequency.MONTHLY,
                Frequency.QUARTERLY, Frequency.YEARLY)) {
            String cron = AppliedCovenantService.resolveCron(apply("1", f, null));
            assertThat(cron).isEqualTo(f.defaultCron());
        }
    }

    // ---- US-CM-25: per-CP definition change log ------------------------------------------------

    @Test
    void definitionHistoryMapsAuditRowsNewestFirst() {
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-06-01T00:00:00Z");
        when(thresholdAuditRepo.findByAppliedCovenantIdOrderByChangedDateDesc("100")).thenReturn(List.of(
                new ThresholdAudit("CP-1", "1", "100", ThresholdSource.USER,
                        ThresholdAudit.Event.APPLIED, "DSCR > 1", "DSCR > 1.5", null, null,
                        "alice", newer),
                new ThresholdAudit("CP-1", "1", "100", ThresholdSource.ML,
                        ThresholdAudit.Event.APPLIED, "DSCR > 1", "DSCR > 1.2", null, null,
                        "bob", older)));

        List<DefinitionChangeRow> rows = service.definitionHistory("100");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).changedAt()).isEqualTo(newer);
        assertThat(rows.get(0).changedBy()).isEqualTo("alice");
        assertThat(rows.get(0).oldExpression()).isEqualTo("DSCR > 1");
        assertThat(rows.get(0).newExpression()).isEqualTo("DSCR > 1.5");
        assertThat(rows.get(0).source()).isEqualTo(ThresholdSource.USER);
        assertThat(rows.get(1).changedBy()).isEqualTo("bob");
        assertThat(rows.get(1).source()).isEqualTo(ThresholdSource.ML);
    }

    /** AC7: a covenant applied with no override has an empty log. */
    @Test
    void definitionHistoryIsEmptyWhenNoOverrideWasCommitted() {
        when(thresholdAuditRepo.findByAppliedCovenantIdOrderByChangedDateDesc("101"))
                .thenReturn(List.of());

        assertThat(service.definitionHistory("101")).isEmpty();
    }

    // ---- US-FREQ-07: per-CP monitoring start date --------------------------------------------

    @Test
    void appliesWithExplicitFutureStartDate() {
        CovenantMaster master = newMaster("1", true);
        when(masterRepo.findById("1")).thenReturn(Optional.of(master));
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(false);
        when(appliedRepo.saveAndFlush(any(AppliedCovenant.class))).thenAnswer(inv -> inv.getArgument(0));

        LocalDate future = LocalDate.now().plusDays(30);
        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(
                new ApplyCovenantRequest("1", Frequency.DAILY, null, null, future)));
        AppliedCovenantResponse r = service.applyBulk("CP-1", req).get(0);

        assertThat(r.startDate()).isEqualTo(future);
    }

    @Test
    void defaultsStartDateToTodayWhenOmitted() {
        CovenantMaster master = newMaster("1", true);
        when(masterRepo.findById("1")).thenReturn(Optional.of(master));
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(false);
        when(appliedRepo.saveAndFlush(any(AppliedCovenant.class))).thenAnswer(inv -> inv.getArgument(0));

        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(apply("1", Frequency.DAILY, null)));
        AppliedCovenantResponse r = service.applyBulk("CP-1", req).get(0);

        assertThat(r.startDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void rejectsPastStartDate() {
        CovenantMaster master = newMaster("1", true);
        when(masterRepo.findById("1")).thenReturn(Optional.of(master));
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(false);

        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(
                new ApplyCovenantRequest("1", Frequency.DAILY, null, null, LocalDate.now().minusDays(1))));
        assertThatThrownBy(() -> service.applyBulk("CP-1", req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("start date");
        verify(appliedRepo, never()).saveAndFlush(any());
    }

    // ---- CP-level condition type & classification (captured at apply time) --------------------

    @Test
    void appliesWithConditionTypeAndClassification() {
        CovenantMaster master = newMaster("1", true);
        when(masterRepo.findById("1")).thenReturn(Optional.of(master));
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(false);
        when(appliedRepo.saveAndFlush(any(AppliedCovenant.class))).thenAnswer(inv -> inv.getArgument(0));

        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(
                new ApplyCovenantRequest("1", Frequency.DAILY, null, null, LocalDate.now(),
                        CovenantConditionType.PRECEDENT, CovenantClassification.EXTERNAL)));
        AppliedCovenantResponse r = service.applyBulk("CP-1", req).get(0);

        assertThat(r.conditionType()).isEqualTo(CovenantConditionType.PRECEDENT);
        assertThat(r.classification()).isEqualTo(CovenantClassification.EXTERNAL);
    }

    @Test
    void leavesConditionTypeAndClassificationNullWhenOmitted() {
        CovenantMaster master = newMaster("1", true);
        when(masterRepo.findById("1")).thenReturn(Optional.of(master));
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(false);
        when(appliedRepo.saveAndFlush(any(AppliedCovenant.class))).thenAnswer(inv -> inv.getArgument(0));

        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(apply("1", Frequency.DAILY, null)));
        AppliedCovenantResponse r = service.applyBulk("CP-1", req).get(0);

        assertThat(r.conditionType()).isNull();
        assertThat(r.classification()).isNull();
    }

    // ---- US-ML-13: threshold provenance + APPLIED audit ---------------------------------------

    @Test
    void appliesMlThresholdSourceAndWritesAppliedAudit() {
        CovenantMaster master = newMaster("1", true);
        when(masterRepo.findById("1")).thenReturn(Optional.of(master));
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(false);
        stubSavedWithId("100");

        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(
                new ApplyCovenantRequest("1", Frequency.DAILY, null, "DSCR > 1.7", null, null, null,
                        ThresholdSource.ML)));
        AppliedCovenantResponse r = service.applyBulk("CP-1", req).get(0);

        assertThat(r.thresholdSource()).isEqualTo(ThresholdSource.ML);

        ArgumentCaptor<ThresholdAudit> captor = ArgumentCaptor.forClass(ThresholdAudit.class);
        verify(thresholdAuditRepo).save(captor.capture());
        ThresholdAudit audit = captor.getValue();
        assertThat(audit.getEvent()).isEqualTo(ThresholdAudit.Event.APPLIED);
        assertThat(audit.getSource()).isEqualTo(ThresholdSource.ML);
        assertThat(audit.getCpId()).isEqualTo("CP-1");
        assertThat(audit.getCovenantMasterId()).isEqualTo("1");
        assertThat(audit.getAppliedCovenantId()).isEqualTo("100");
        assertThat(audit.getOldExpression()).isEqualTo("DSCR > 1");
        assertThat(audit.getNewExpression()).isEqualTo("DSCR > 1.7");
        assertThat(audit.getChangedBy()).isNotBlank();
        assertThat(audit.getChangedDate()).isNotNull();
    }

    @Test
    void defaultsThresholdSourceToUserWhenOverrideHasNoSource() {
        CovenantMaster master = newMaster("1", true);
        when(masterRepo.findById("1")).thenReturn(Optional.of(master));
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(false);
        stubSavedWithId("100");

        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(
                new ApplyCovenantRequest("1", Frequency.DAILY, null, "DSCR > 1.7")));
        AppliedCovenantResponse r = service.applyBulk("CP-1", req).get(0);

        assertThat(r.thresholdSource()).isEqualTo(ThresholdSource.USER);
        ArgumentCaptor<ThresholdAudit> captor = ArgumentCaptor.forClass(ThresholdAudit.class);
        verify(thresholdAuditRepo).save(captor.capture());
        assertThat(captor.getValue().getSource()).isEqualTo(ThresholdSource.USER);
    }

    @Test
    void masterSourceAndNoAuditWhenNoOverride() {
        CovenantMaster master = newMaster("1", true);
        when(masterRepo.findById("1")).thenReturn(Optional.of(master));
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(false);
        when(appliedRepo.saveAndFlush(any(AppliedCovenant.class))).thenAnswer(inv -> inv.getArgument(0));

        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(apply("1", Frequency.DAILY, null)));
        AppliedCovenantResponse r = service.applyBulk("CP-1", req).get(0);

        assertThat(r.thresholdSource()).isEqualTo(ThresholdSource.MASTER);
        verify(thresholdAuditRepo, never()).save(any());
    }

    @Test
    void ignoresRequestedSourceWhenNoOverride() {
        CovenantMaster master = newMaster("1", true);
        when(masterRepo.findById("1")).thenReturn(Optional.of(master));
        when(appliedRepo.existsByCpIdAndCovenantMasterId("CP-1", "1")).thenReturn(false);
        when(appliedRepo.saveAndFlush(any(AppliedCovenant.class))).thenAnswer(inv -> inv.getArgument(0));

        BulkApplyCovenantsRequest req = new BulkApplyCovenantsRequest(List.of(
                new ApplyCovenantRequest("1", Frequency.DAILY, null, null, null, null, null,
                        ThresholdSource.ML)));
        AppliedCovenantResponse r = service.applyBulk("CP-1", req).get(0);

        assertThat(r.thresholdSource()).isEqualTo(ThresholdSource.MASTER);
        verify(thresholdAuditRepo, never()).save(any());
    }

    private void stubSavedWithId(String id) {
        when(appliedRepo.saveAndFlush(any(AppliedCovenant.class))).thenAnswer(inv -> {
            AppliedCovenant a = inv.getArgument(0);
            Field f = AppliedCovenant.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(a, id);
            return a;
        });
    }

    private static CovenantMaster newMaster(String id, boolean active) {
        CovenantMaster m = new CovenantMaster();
        try {
            Field f = CovenantMaster.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(m, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        m.setName("Master " + id);
        m.setCategory(CovenantCategory.FINANCIAL);
        m.setConditionExpression("DSCR > 1");
        m.setBusinessSegments(List.of());
        m.setActive(active);
        return m;
    }

    private static AppliedCovenant newApplied(String id, CovenantMaster master) {
        AppliedCovenant a = new AppliedCovenant();
        try {
            Field f = AppliedCovenant.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(a, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        a.setCpId("CP-1");
        a.setCovenantMaster(master);
        a.setFrequency(Frequency.DAILY);
        a.setCronExpression(Frequency.DAILY.defaultCron());
        a.setStartDate(java.time.LocalDate.now());
        a.setStatus(AppliedCovenantStatus.ACTIVE);
        a.setSnapshotJson("{}");
        return a;
    }
}
