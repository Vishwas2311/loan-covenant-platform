package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.exception.DuplicateResourceException;
import com.crisil.covenant.exception.ResourceNotFoundException;
import com.crisil.covenant.repository.ExpressionChangeLogRepository;
import com.crisil.covenant.dto.CovenantResponse;
import com.crisil.covenant.dto.CreateCovenantRequest;
import com.crisil.covenant.dto.UpdateCovenantRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.repository.CovenantMasterRepository;

@ExtendWith(MockitoExtension.class)
class CovenantMasterServiceTest {

    @Mock private CovenantMasterRepository repository;
    @Mock private CovenantConditionValidator conditionValidator;
    @Mock private ExpressionChangeLogRepository changeLogRepository;
    @Mock private com.crisil.covenant.repository.AppliedCovenantRepository appliedRepository;

    @InjectMocks private CovenantMasterService service;

    private CreateCovenantRequest createRequest;

    @BeforeEach
    void setUp() {
        createRequest = new CreateCovenantRequest(
                "DSCR check", CovenantCategory.FINANCIAL,
                "Debt service coverage ratio", "DSCR > 1.2", List.of("RETAIL"));
        // Formula validity/parsing is CovenantConditionValidator's job (tested there); a valid
        // expression is the default here — validateOrThrow is a no-op unless a test makes it throw.
        lenient().when(conditionValidator.referencedMeasures(anyString())).thenReturn(List.of("DSCR"));
    }

    /** Make the shared validator reject any expression, as it does for a malformed formula. */
    private void rejectFormula(String message) {
        doThrow(new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RULE_EXPRESSION", message))
                .when(conditionValidator).validateOrThrow(anyString());
    }

    @Test
    void createPersistsAndReturnsResponse() {
        when(repository.existsByNameIgnoreCase("DSCR check")).thenReturn(false);
        when(repository.saveAndFlush(any(CovenantMaster.class))).thenAnswer(inv -> inv.getArgument(0));

        CovenantResponse response = service.create(createRequest);

        assertThat(response.name()).isEqualTo("DSCR check");
        assertThat(response.conditionExpression()).isEqualTo("DSCR > 1.2");
        assertThat(response.referencedMeasures()).containsExactly("DSCR");
        assertThat(response.active()).isTrue();
        verify(repository).saveAndFlush(any(CovenantMaster.class));
    }

    @Test
    void createRejectsDuplicateName() {
        when(repository.existsByNameIgnoreCase("DSCR check")).thenReturn(true);
        assertThatThrownBy(() -> service.create(createRequest))
                .isInstanceOf(DuplicateResourceException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsInvalidExpression() {
        when(repository.existsByNameIgnoreCase("DSCR check")).thenReturn(false);
        rejectFormula("Unknown measure 'foo'");
        assertThatThrownBy(() -> service.create(createRequest))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unknown measure 'foo'");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateRejectsInvalidExpression() {
        CovenantMaster existing = newEntity("1", "name");
        when(repository.findById("1")).thenReturn(Optional.of(existing));
        rejectFormula("Expected expression");
        UpdateCovenantRequest req = new UpdateCovenantRequest(
                "name", CovenantCategory.FINANCIAL, null, "DSCR >", null);
        assertThatThrownBy(() -> service.update("1", req)).isInstanceOf(ApiException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateModifiesFields() {
        CovenantMaster existing = newEntity("1", "old name");
        when(repository.findById("1")).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any(CovenantMaster.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateCovenantRequest update = new UpdateCovenantRequest(
                "new name", CovenantCategory.NON_FINANCIAL,
                "Auditor check", "auditor == 'PwC'", List.of("CORPORATE"));

        CovenantResponse response = service.update("1", update);

        assertThat(response.name()).isEqualTo("new name");
        assertThat(response.category()).isEqualTo(CovenantCategory.NON_FINANCIAL);
        assertThat(response.conditionExpression()).isEqualTo("auditor == 'PwC'");
        assertThat(response.referencedMeasures()).containsExactly("DSCR");
    }

    @Test
    void updateLogsExpressionChangeWhenFormulaChanges() {
        CovenantMaster existing = newEntity("1", "name");
        when(repository.findById("1")).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any(CovenantMaster.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update("1", new UpdateCovenantRequest(
                "name", CovenantCategory.FINANCIAL, null, "DSCR > 2", null));

        verify(changeLogRepository).save(any());
    }

    @Test
    void updateDoesNotLogWhenFormulaUnchanged() {
        CovenantMaster existing = newEntity("1", "name");
        when(repository.findById("1")).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any(CovenantMaster.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update("1", new UpdateCovenantRequest(
                "name", CovenantCategory.FINANCIAL, null, "DSCR > 1", null));

        verify(changeLogRepository, never()).save(any());
    }

    @Test
    void updateWithSameNameDoesNotCheckDuplicate() {
        CovenantMaster existing = newEntity("1", "name");
        when(repository.findById("1")).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any(CovenantMaster.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update("1", new UpdateCovenantRequest(
                "name", CovenantCategory.FINANCIAL, null, "DSCR > 1", null));
        verify(repository, never()).existsByNameIgnoreCase(any());
    }

    @Test
    void updateRejectsRenameThatCollides() {
        CovenantMaster existing = newEntity("1", "old");
        when(repository.findById("1")).thenReturn(Optional.of(existing));
        when(repository.existsByNameIgnoreCase("collision")).thenReturn(true);

        UpdateCovenantRequest update = new UpdateCovenantRequest(
                "collision", CovenantCategory.FINANCIAL, null, "DSCR > 1", null);
        assertThatThrownBy(() -> service.update("1", update))
                .isInstanceOf(DuplicateResourceException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateMissingThrowsNotFound() {
        when(repository.findById("42")).thenReturn(Optional.empty());
        UpdateCovenantRequest update = new UpdateCovenantRequest(
                "x", CovenantCategory.FINANCIAL, null, "DSCR > 1", null);
        assertThatThrownBy(() -> service.update("42", update))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void setActiveTogglesFlag() {
        CovenantMaster existing = newEntity("1", "n");
        existing.setActive(true);
        when(repository.findById("1")).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any(CovenantMaster.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.setActive("1", false).active()).isFalse();
    }

    @Test
    void setActiveMissingThrowsNotFound() {
        when(repository.findById("99")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.setActive("99", false))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getReturnsResponse() {
        when(repository.findById("1")).thenReturn(Optional.of(newEntity("1", "n")));
        assertThat(service.get("1").name()).isEqualTo("n");
    }

    @Test
    void getMissingThrowsNotFound() {
        when(repository.findById("99")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("99")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchDelegatesToRepositoryWithSpecification() {
        Page<CovenantMaster> page = new PageImpl<>(List.of(newEntity("1", "a"), newEntity("2", "b")));
        when(repository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);

        Page<CovenantResponse> result = service.search(
                "DSCR", CovenantCategory.FINANCIAL, "RETAIL", true, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).name()).isEqualTo("a");
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchAcceptsAllNullFilters() {
        when(repository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));
        assertThat(service.search(null, null, null, null, PageRequest.of(0, 10)).getContent())
                .isEmpty();
    }

    @Test
    void usageTrimsSampleToTwentyFive() {
        when(repository.findById("9")).thenReturn(Optional.of(newEntity("9", "n")));
        when(appliedRepository.countByCovenantMasterId("9")).thenReturn(40L);
        when(appliedRepository.countByCovenantMasterIdAndStatus("9", com.crisil.covenant.enums.AppliedCovenantStatus.ACTIVE)).thenReturn(30L);
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            ids.add("CP-" + i);
        }
        when(appliedRepository.findCpIdsByCovenantMasterId("9")).thenReturn(ids);
        assertThat(service.usage("9").sampleCpIds()).hasSize(25);
    }

    @Test
    void usageKeepsSmallSample() {
        when(repository.findById("9")).thenReturn(Optional.of(newEntity("9", "n")));
        when(appliedRepository.countByCovenantMasterId("9")).thenReturn(2L);
        when(appliedRepository.countByCovenantMasterIdAndStatus(anyString(), any())).thenReturn(1L);
        when(appliedRepository.findCpIdsByCovenantMasterId("9")).thenReturn(List.of("CP-1", "CP-2"));
        assertThat(service.usage("9").sampleCpIds()).hasSize(2);
    }

    @Test
    void auditTrailDelegatesToChangeLogRepository() {
        when(repository.findById("1")).thenReturn(Optional.of(newEntity("1", "n")));
        when(changeLogRepository.findByCovenantIdOrderByChangedDateDesc("1")).thenReturn(List.of());
        assertThat(service.auditTrail("1")).isEmpty();
    }

    @Test
    void createManualSkipsValidationAndStoresNoMeasures() {
        when(repository.existsByNameIgnoreCase(anyString())).thenReturn(false);
        when(repository.saveAndFlush(any(CovenantMaster.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateCovenantRequest manual = new CreateCovenantRequest(
                "Board resolution", CovenantCategory.NON_FINANCIAL,
                "Confirm board resolution within 30 days", "Any free text — not a formula!",
                List.of("ALL"), false, AssessmentType.MANUAL,
                null, null, null, null, null, null, null);

        CovenantResponse response = service.create(manual);

        assertThat(response.assessmentType()).isEqualTo(AssessmentType.MANUAL);
        assertThat(response.referencedMeasures()).isEmpty();
        assertThat(response.conditionExpression()).isEqualTo("Any free text — not a formula!");
        verify(conditionValidator, never()).validateOrThrow(anyString());
        verify(conditionValidator, never()).referencedMeasures(anyString());
    }

    @Test
    void createNormalisesWildcardAndPersistsEligibility() {
        when(repository.existsByNameIgnoreCase(anyString())).thenReturn(false);
        when(repository.saveAndFlush(any(CovenantMaster.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateCovenantRequest req = new CreateCovenantRequest(
                "Geo rule", CovenantCategory.FINANCIAL, "desc", "DSCR > 1.2",
                List.of("ALL", "RETAIL"), true, AssessmentType.AUTO,
                List.of("INFRASTRUCTURE"), List.of("IN", "AE"), List.of(),
                List.of("TERM_LOAN"), null, null, null);

        CovenantResponse r = service.create(req);

        assertThat(r.islamicBanking()).isTrue();
        assertThat(r.businessSegments()).containsExactly("ALL");
        assertThat(r.industries()).containsExactly("INFRASTRUCTURE");
        assertThat(r.countriesOfRisk()).containsExactly("IN", "AE");
        assertThat(r.countriesOfIncorporation()).containsExactly("ALL");
        assertThat(r.creditProposalTypes()).containsExactly("TERM_LOAN");
        assertThat(r.legalEntityTypes()).containsExactly("ALL");
        assertThat(r.facilityTypes()).containsExactly("ALL");
        assertThat(r.collateralTypes()).containsExactly("ALL");
    }

    @Test
    void updateLogsEligibilityChangeEvenWhenFormulaUnchanged() {
        CovenantMaster existing = newEntity("1", "name");
        when(repository.findById("1")).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any(CovenantMaster.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateCovenantRequest req = new UpdateCovenantRequest(
                "name", CovenantCategory.FINANCIAL, "d", "DSCR > 1",
                null, false, AssessmentType.AUTO,
                List.of("TELECOM"), null, null, null, null, null, null);

        CovenantResponse r = service.update("1", req);

        assertThat(r.industries()).containsExactly("TELECOM");
        verify(changeLogRepository).save(any());
    }

    @Test
    void updateManualSkipsValidation() {
        CovenantMaster existing = newEntity("1", "n");
        when(repository.findById("1")).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any(CovenantMaster.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateCovenantRequest manual = new UpdateCovenantRequest(
                "n", CovenantCategory.NON_FINANCIAL, "d", "free text instruction",
                null, false, AssessmentType.MANUAL, null, null, null, null, null, null, null);

        CovenantResponse r = service.update("1", manual);

        assertThat(r.assessmentType()).isEqualTo(AssessmentType.MANUAL);
        assertThat(r.referencedMeasures()).isEmpty();
        verify(conditionValidator, never()).validateOrThrow(anyString());
    }

    // ---- US-FREQ-07: covenant master default monitoring frequency ----------------------------

    @Test
    void createDefaultsFrequencyToMonthlyWhenOmitted() {
        when(repository.existsByNameIgnoreCase(anyString())).thenReturn(false);
        when(repository.saveAndFlush(any(CovenantMaster.class))).thenAnswer(inv -> inv.getArgument(0));
        // createRequest uses the pre-frequency constructor → defaults to Monthly (AC6).
        CovenantResponse r = service.create(createRequest);
        assertThat(r.defaultFrequency()).isEqualTo(Frequency.MONTHLY);
        assertThat(r.defaultCron()).isNull();
    }

    @Test
    void createPersistsExplicitDefaultFrequency() {
        when(repository.existsByNameIgnoreCase(anyString())).thenReturn(false);
        when(repository.saveAndFlush(any(CovenantMaster.class))).thenAnswer(inv -> inv.getArgument(0));
        CovenantResponse r = service.create(withDefaultFrequency(Frequency.QUARTERLY, null));
        assertThat(r.defaultFrequency()).isEqualTo(Frequency.QUARTERLY);
        assertThat(r.defaultCron()).isNull();
    }

    @Test
    void createCustomDefaultFrequencyStoresValidCron() {
        when(repository.existsByNameIgnoreCase(anyString())).thenReturn(false);
        when(repository.saveAndFlush(any(CovenantMaster.class))).thenAnswer(inv -> inv.getArgument(0));
        CovenantResponse r = service.create(withDefaultFrequency(Frequency.CUSTOM, "0 0 6 1 * ?"));
        assertThat(r.defaultFrequency()).isEqualTo(Frequency.CUSTOM);
        assertThat(r.defaultCron()).isEqualTo("0 0 6 1 * ?");
    }

    @Test
    void createCustomDefaultFrequencyRejectsMissingCron() {
        when(repository.existsByNameIgnoreCase(anyString())).thenReturn(false);
        assertThatThrownBy(() -> service.create(withDefaultFrequency(Frequency.CUSTOM, "   ")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("valid Quartz cron");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void createCustomDefaultFrequencyRejectsInvalidCron() {
        when(repository.existsByNameIgnoreCase(anyString())).thenReturn(false);
        assertThatThrownBy(() -> service.create(withDefaultFrequency(Frequency.CUSTOM, "not a cron")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("valid Quartz cron");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateCanChangeDefaultFrequency() {
        CovenantMaster existing = newEntity("1", "name");
        when(repository.findById("1")).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any(CovenantMaster.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateCovenantRequest req = new UpdateCovenantRequest(
                "name", CovenantCategory.FINANCIAL, "d", "DSCR > 1",
                null, false, AssessmentType.AUTO, null, null, null, null, null, null, null,
                Frequency.YEARLY, null);
        CovenantResponse r = service.update("1", req);
        assertThat(r.defaultFrequency()).isEqualTo(Frequency.YEARLY);
    }

    private static CreateCovenantRequest withDefaultFrequency(Frequency frequency, String cron) {
        return new CreateCovenantRequest(
                "DSCR check", CovenantCategory.FINANCIAL, "desc", "DSCR > 1.2",
                List.of("ALL"), false, AssessmentType.AUTO,
                null, null, null, null, null, null, null,
                frequency, cron);
    }

    private CovenantMaster newEntity(String id, String name) {
        CovenantMaster e = new CovenantMaster();
        try {
            java.lang.reflect.Field f = CovenantMaster.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(e, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        e.setName(name);
        e.setCategory(CovenantCategory.FINANCIAL);
        e.setDescription(null);
        e.setConditionExpression("DSCR > 1");
        e.setReferencedMeasures(List.of("DSCR"));
        e.setBusinessSegments(List.of());
        e.setActive(true);
        return e;
    }
}
