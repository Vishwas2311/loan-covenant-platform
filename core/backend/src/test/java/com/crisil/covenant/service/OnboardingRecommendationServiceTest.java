package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.domain.RecommendedCovenant;
import com.crisil.covenant.dto.ExtractionRecommendation;
import com.crisil.covenant.dto.MlCovenantContext;
import com.crisil.covenant.dto.MlThresholdRecommendation;
import com.crisil.covenant.dto.OnboardingRecommendationRow;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.enums.ThresholdSource;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.CovenantMasterRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OnboardingRecommendationServiceTest {

    @Mock private RecommendationService recommendationService;
    @Mock private MlThresholdService mlThresholdService;
    @Mock private AppliedCovenantRepository appliedRepo;
    @Mock private CovenantMasterRepository masterRepo;
    @Mock private ExtractionRecommendationService extractionService;

    @InjectMocks private OnboardingRecommendationService service;

    @Test
    void returnsMasterRowsUnchangedWhenMlAddsNothing() {
        when(recommendationService.recommendForCp("CP-1"))
                .thenReturn(List.of(masterRec("1", "DSCR", false), masterRec("2", "Board approval", false)));
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of());
        CovenantMaster m1 = master("1", "DSCR > 1.5", AssessmentType.AUTO, true, false);
        CovenantMaster m2 = master("2", "Board approval on file", AssessmentType.MANUAL, true, false);
        when(masterRepo.findAllById(anyList())).thenReturn(List.of(m1, m2));
        when(mlThresholdService.recommend(eq("CP-1"), anyList())).thenReturn(List.of());

        List<OnboardingRecommendationRow> rows = service.recommend("CP-1");

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.source()).isEqualTo("RECOMMENDED");
            assertThat(r.thresholdSource()).isEqualTo(ThresholdSource.MASTER);
            assertThat(r.conditionOverride()).isNull();
            assertThat(r.mlRecommendedExpression()).isNull();
            assertThat(r.alreadyApplied()).isFalse();
        });
        assertThat(rows.get(1).assessmentType()).isEqualTo(AssessmentType.MANUAL);
    }

    @Test
    void mlThresholdOverrideTagsTheMasterRowAsMl() {
        CovenantMaster m1 = master("1", "DSCR > 1.5", AssessmentType.AUTO, true, false);
        when(recommendationService.recommendForCp("CP-1"))
                .thenReturn(List.of(masterRec("1", "DSCR", false)));
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of());
        when(masterRepo.findAllById(anyList())).thenReturn(List.of(m1));
        when(mlThresholdService.recommend(eq("CP-1"), anyList()))
                .thenReturn(List.of(rec("1", "DSCR > 1.6")));

        OnboardingRecommendationRow row = service.recommend("CP-1").get(0);

        assertThat(row.source()).isEqualTo("ML");
        assertThat(row.thresholdSource()).isEqualTo(ThresholdSource.ML);
        assertThat(row.conditionOverride()).isEqualTo("DSCR > 1.6");
        assertThat(row.mlRecommendedExpression()).isEqualTo("DSCR > 1.6");
        assertThat(row.matchReason()).isEqualTo("matched DSCR"); // master reason preserved
    }

    @Test
    void mlThresholdIsIgnoredForAManualMasterRow() {
        CovenantMaster m2 = master("2", "Board approval on file", AssessmentType.MANUAL, true, false);
        when(recommendationService.recommendForCp("CP-1"))
                .thenReturn(List.of(masterRec("2", "Board approval", false)));
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of());
        when(masterRepo.findAllById(anyList())).thenReturn(List.of(m2));
        when(mlThresholdService.recommend(eq("CP-1"), anyList()))
                .thenReturn(List.of(rec("2", "something")));

        OnboardingRecommendationRow row = service.recommend("CP-1").get(0);

        assertThat(row.source()).isEqualTo("RECOMMENDED");
        assertThat(row.thresholdSource()).isEqualTo(ThresholdSource.MASTER);
        assertThat(row.conditionOverride()).isNull();
    }

    @Test
    void appendsBrandNewAutoCovenantFromMl() {
        CovenantMaster m1 = master("1", "DSCR > 1.5", AssessmentType.AUTO, true, false);
        CovenantMaster m50 = master("50", "leverage < 5", AssessmentType.AUTO, true, false);
        when(recommendationService.recommendForCp("CP-1"))
                .thenReturn(List.of(masterRec("1", "DSCR", false)));
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of());
        when(masterRepo.findAllById(anyList())).thenReturn(List.of(m1));
        when(mlThresholdService.recommend(eq("CP-1"), anyList()))
                .thenReturn(List.of(rec("50", "leverage < 4")));
        when(masterRepo.findById("50")).thenReturn(Optional.of(m50));

        List<OnboardingRecommendationRow> rows = service.recommend("CP-1");

        assertThat(rows).hasSize(2);
        OnboardingRecommendationRow added = rows.get(1);
        assertThat(added.covenantId()).isEqualTo("50");
        assertThat(added.source()).isEqualTo("ML");
        assertThat(added.thresholdSource()).isEqualTo(ThresholdSource.ML);
        assertThat(added.conditionOverride()).isEqualTo("leverage < 4");
        assertThat(added.mlRecommendedExpression()).isEqualTo("leverage < 4");
        assertThat(added.assessmentType()).isEqualTo(AssessmentType.AUTO);
    }

    @Test
    void appendsBrandNewManualCovenantWithoutThreshold() {
        CovenantMaster m60 = master("60", "Insurance active", AssessmentType.MANUAL, true, false);
        when(recommendationService.recommendForCp("CP-1")).thenReturn(List.of());
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of());
        when(masterRepo.findAllById(anyList())).thenReturn(List.of());
        when(mlThresholdService.recommend(eq("CP-1"), anyList()))
                .thenReturn(List.of(new MlThresholdRecommendation("60", null, new BigDecimal("0.9"), "add clause")));
        when(masterRepo.findById("60")).thenReturn(Optional.of(m60));

        OnboardingRecommendationRow row = service.recommend("CP-1").get(0);

        assertThat(row.covenantId()).isEqualTo("60");
        assertThat(row.source()).isEqualTo("ML");
        assertThat(row.thresholdSource()).isEqualTo(ThresholdSource.MASTER);
        assertThat(row.conditionOverride()).isNull();
        assertThat(row.mlRecommendedExpression()).isNull();
        assertThat(row.matchReason()).isEqualTo("add clause"); // ML rationale surfaced as the reason
    }

    @Test
    void skipsNewMlCovenantThatIsUnknownOrInactive() {
        CovenantMaster m70 = master("70", "DSCR > 2", AssessmentType.AUTO, false, false); // inactive
        when(recommendationService.recommendForCp("CP-1")).thenReturn(List.of());
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of());
        when(masterRepo.findAllById(anyList())).thenReturn(List.of());
        when(mlThresholdService.recommend(eq("CP-1"), anyList()))
                .thenReturn(List.of(rec("70", "DSCR > 2"), rec("80", "ICR > 3")));
        when(masterRepo.findById("70")).thenReturn(Optional.of(m70));
        when(masterRepo.findById("80")).thenReturn(Optional.empty()); // unknown

        assertThat(service.recommend("CP-1")).isEmpty();
    }

    @Test
    void flagsAlreadyAppliedAndExcludesItFromTheMlStage() {
        when(recommendationService.recommendForCp("CP-1"))
                .thenReturn(List.of(masterRec("1", "DSCR", false), masterRec("2", "ICR", false)));
        AppliedCovenant applied = mock(AppliedCovenant.class);
        CovenantMaster appliedMaster = mock(CovenantMaster.class);
        lenient().when(appliedMaster.getId()).thenReturn("1");
        lenient().when(applied.getCovenantMaster()).thenReturn(appliedMaster);
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of(applied));
        CovenantMaster m1 = master("1", "DSCR > 1.5", AssessmentType.AUTO, true, false);
        CovenantMaster m2 = master("2", "ICR > 2", AssessmentType.AUTO, true, false);
        when(masterRepo.findAllById(anyList())).thenReturn(List.of(m1, m2));
        when(mlThresholdService.recommend(eq("CP-1"), anyList())).thenReturn(List.of());

        List<OnboardingRecommendationRow> rows = service.recommend("CP-1");

        assertThat(rows).extracting(OnboardingRecommendationRow::covenantId)
                .containsExactly("1", "2");
        assertThat(rows.get(0).alreadyApplied()).isTrue();
        assertThat(rows.get(1).alreadyApplied()).isFalse();

        // The applied covenant "1" is not offered to the ML stage; only "2" is in-scope.
        ArgumentCaptor<List<MlCovenantContext>> captor = ArgumentCaptor.forClass(List.class);
        verify(mlThresholdService).recommend(eq("CP-1"), captor.capture());
        assertThat(captor.getValue()).extracting(MlCovenantContext::covenantMasterId).containsExactly("2");
    }

    @Test
    void extractedThresholdWinsOverMlAndCarriesEvidence() {
        CovenantMaster m1 = master("1", "DSCR > 1.5", AssessmentType.AUTO, true, false);
        when(recommendationService.recommendForCp("CP-1")).thenReturn(List.of(masterRec("1", "DSCR", false)));
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of());
        when(masterRepo.findAllById(anyList())).thenReturn(List.of(m1));
        when(mlThresholdService.recommend(eq("CP-1"), anyList())).thenReturn(List.of(rec("1", "DSCR > 1.6")));
        ExtractionRecommendation.Source src =
                new ExtractionRecommendation.Source("D1", "F1", 12, "not less than 1.25");
        when(extractionService.recommend("CP-1")).thenReturn(List.of(
                new ExtractionRecommendation("1", "DSCR >= 1.25", 0.93, "clause 7.1", "QUARTERLY", src, null)));

        OnboardingRecommendationRow row = service.recommend("CP-1").get(0);

        assertThat(row.source()).isEqualTo("EXTRACTED");
        assertThat(row.conditionOverride()).isEqualTo("DSCR >= 1.25");
        assertThat(row.thresholdSource()).isEqualTo(ThresholdSource.EXTRACTED);
        assertThat(row.mlRecommendedExpression()).isEqualTo("DSCR > 1.6");
        assertThat(row.extractedExpression()).isEqualTo("DSCR >= 1.25");
        assertThat(row.extractionConfidence()).isEqualTo(0.93);
        assertThat(row.evidence()).isEqualTo(src);
        assertThat(row.defaultFrequency()).isEqualTo(Frequency.QUARTERLY);
        assertThat(row.defaultCron()).isEqualTo(Frequency.QUARTERLY.defaultCron());
    }

    @Test
    void extractedManualCovenantKeepsNoOverride() {
        CovenantMaster m2 = master("2", "Board approval", AssessmentType.MANUAL, true, false);
        when(recommendationService.recommendForCp("CP-1")).thenReturn(List.of(masterRec("2", "Board", false)));
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of());
        when(masterRepo.findAllById(anyList())).thenReturn(List.of(m2));
        when(mlThresholdService.recommend(eq("CP-1"), anyList())).thenReturn(List.of());
        when(extractionService.recommend("CP-1")).thenReturn(List.of(
                new ExtractionRecommendation("2", null, 0.8, null, null, null, null)));

        OnboardingRecommendationRow row = service.recommend("CP-1").get(0);

        assertThat(row.source()).isEqualTo("EXTRACTED");
        assertThat(row.conditionOverride()).isNull();
        assertThat(row.thresholdSource()).isEqualTo(ThresholdSource.MASTER);
        assertThat(row.defaultFrequency()).isEqualTo(Frequency.MONTHLY);
    }

    @Test
    void addsExtractedLibraryAndCustomCovenantsNotOtherwiseRecommended() {
        when(recommendationService.recommendForCp("CP-1")).thenReturn(List.of());
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of());
        when(masterRepo.findAllById(anyList())).thenReturn(List.of());
        when(mlThresholdService.recommend(eq("CP-1"), anyList())).thenReturn(List.of());
        CovenantMaster m3 = master("3", "LEV < 4", AssessmentType.AUTO, true, false);
        when(masterRepo.findById("3")).thenReturn(Optional.of(m3));
        when(masterRepo.findById("4")).thenReturn(Optional.empty());
        ExtractionRecommendation.Source src = new ExtractionRecommendation.Source("D1", "F1", 3, "q");
        when(extractionService.recommend("CP-1")).thenReturn(List.of(
                new ExtractionRecommendation("3", "LEV <= 3.5", 0.9, null, "MONTHLY", src, null),
                new ExtractionRecommendation("4", "X > 1", 0.9, null, null, null, null),
                new ExtractionRecommendation(null, "CAPEX <= 100", 0.7, null, "ANNUAL", null,
                        new ExtractionRecommendation.CustomCovenant("Capex cap", "Capex limit", "AUTO")),
                new ExtractionRecommendation(null, null, 0.6, null, "weird", null,
                        new ExtractionRecommendation.CustomCovenant("Insurance", null, "MANUAL"))));

        List<OnboardingRecommendationRow> rows = service.recommend("CP-1");

        assertThat(rows).hasSize(3);
        OnboardingRecommendationRow lib = rows.get(0);
        assertThat(lib.covenantId()).isEqualTo("3");
        assertThat(lib.source()).isEqualTo("EXTRACTED");
        assertThat(lib.matchReason()).isEqualTo("From loan agreement, page 3");
        assertThat(lib.conditionOverride()).isEqualTo("LEV <= 3.5");
        assertThat(lib.defaultCron()).isNull();
        OnboardingRecommendationRow capex = rows.get(1);
        assertThat(capex.covenantId()).isNull();
        assertThat(capex.source()).isEqualTo("EXTRACTED_CUSTOM");
        assertThat(capex.assessmentType()).isEqualTo(AssessmentType.AUTO);
        assertThat(capex.thresholdSource()).isEqualTo(ThresholdSource.EXTRACTED);
        assertThat(capex.defaultFrequency()).isEqualTo(Frequency.YEARLY);
        assertThat(capex.customDefinition()).isEqualTo("Capex limit");
        assertThat(capex.matchReason()).isEqualTo("From loan agreement");
        OnboardingRecommendationRow insurance = rows.get(2);
        assertThat(insurance.assessmentType()).isEqualTo(AssessmentType.MANUAL);
        assertThat(insurance.thresholdSource()).isEqualTo(ThresholdSource.MASTER);
        assertThat(insurance.defaultFrequency()).isEqualTo(Frequency.QUARTERLY);
    }

    @Test
    void frequencyLabelsMapToSchedules() {
        assertThat(OnboardingRecommendationService.frequencyOf(null, Frequency.DAILY)).isEqualTo(Frequency.DAILY);
        assertThat(OnboardingRecommendationService.frequencyOf(" ", Frequency.DAILY)).isEqualTo(Frequency.DAILY);
        assertThat(OnboardingRecommendationService.frequencyOf("ANNUALLY", null)).isEqualTo(Frequency.YEARLY);
        assertThat(OnboardingRecommendationService.frequencyOf("ANNUAL", null)).isEqualTo(Frequency.YEARLY);
        assertThat(OnboardingRecommendationService.frequencyOf("WEEKLY", null)).isEqualTo(Frequency.WEEKLY);
        assertThat(OnboardingRecommendationService.frequencyOf("weekly", Frequency.DAILY)).isEqualTo(Frequency.DAILY);
        assertThat(OnboardingRecommendationService.frequencyOf("CUSTOM", Frequency.DAILY))
                .isEqualTo(Frequency.DAILY);
    }

    @Test
    void skipsExtractedCustomCovenantAlreadyCreatedForTheCp() {
        // Applying an EXTRACTED_CUSTOM row creates a CP-scoped custom master with the same name; the next
        // visit must not offer the agreement's covenant again, or a second apply would duplicate it.
        when(recommendationService.recommendForCp("CP-1")).thenReturn(List.of(masterRec("9", "Capex cap", true)));
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of());
        when(masterRepo.findAllById(anyList())).thenReturn(List.of());
        when(mlThresholdService.recommend(eq("CP-1"), anyList())).thenReturn(List.of());
        when(extractionService.recommend("CP-1")).thenReturn(List.of(
                new ExtractionRecommendation(null, "CAPEX <= 100", 0.7, null, "ANNUAL", null,
                        new ExtractionRecommendation.CustomCovenant("Capex cap", "Capex limit", "AUTO")),
                new ExtractionRecommendation(null, null, 0.6, null, null, null,
                        new ExtractionRecommendation.CustomCovenant("Insurance", null, "MANUAL"))));

        List<OnboardingRecommendationRow> rows = service.recommend("CP-1");

        assertThat(rows).extracting(OnboardingRecommendationRow::name, OnboardingRecommendationRow::source)
                .containsExactly(tuple("Capex cap", "RECOMMENDED"), tuple("Insurance", "EXTRACTED_CUSTOM"));
    }

    private static RecommendedCovenant masterRec(String id, String name, boolean custom) {
        return new RecommendedCovenant(
                id, name, CovenantCategory.FINANCIAL, null, List.of(),
                "matched " + name, Frequency.MONTHLY, null, custom);
    }

    private static MlThresholdRecommendation rec(String id, String expr) {
        return new MlThresholdRecommendation(id, expr, new BigDecimal("0.9"), "ml");
    }

    private static CovenantMaster master(String id, String expression, AssessmentType type,
                                         boolean active, boolean custom) {
        CovenantMaster m = mock(CovenantMaster.class);
        lenient().when(m.getId()).thenReturn(id);
        lenient().when(m.getName()).thenReturn("cov-" + id);
        lenient().when(m.getConditionExpression()).thenReturn(expression);
        lenient().when(m.getAssessmentType()).thenReturn(type);
        lenient().when(m.getDefaultFrequency()).thenReturn(Frequency.MONTHLY);
        lenient().when(m.getDefaultCron()).thenReturn(null);
        lenient().when(m.isActive()).thenReturn(active);
        lenient().when(m.isCustom()).thenReturn(custom);
        return m;
    }
}
