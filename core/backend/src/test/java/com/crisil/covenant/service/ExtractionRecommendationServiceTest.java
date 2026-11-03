package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.ExtractionRecommendation;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.entity.ThresholdAudit;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.ThresholdSource;
import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.formula.FormulaValidationResult;
import com.crisil.covenant.formula.MeasureCatalog;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.CovenantMasterRepository;
import com.crisil.covenant.repository.ThresholdAuditRepository;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExtractionRecommendationServiceTest {

    @Mock private ExtractionRecommender recommender;
    @Mock private CovenantMasterRepository masterRepo;
    @Mock private AppliedCovenantRepository appliedRepo;
    @Mock private ThresholdAuditRepository auditRepo;
    @Mock private FormulaEngine formulaEngine;
    @Mock private MeasureCatalogService measureCatalog;

    @InjectMocks private ExtractionRecommendationService service;

    @BeforeEach
    void catalog() {
        lenient().when(measureCatalog.activeCatalog()).thenReturn(mock(MeasureCatalog.class));
        lenient().when(formulaEngine.validate(eq("GOOD"), any(), eq(true)))
                .thenReturn(new FormulaValidationResult(true, List.of(), "x", "true"));
        lenient().when(formulaEngine.validate(eq("BAD"), any(), eq(true)))
                .thenReturn(new FormulaValidationResult(false, List.of(), null, null));
    }

    @Test
    void emptyOrNullFromDpsYieldsNothing() {
        when(recommender.recommend("CP-1")).thenReturn(List.of());
        assertThat(service.recommend("CP-1")).isEmpty();
        when(recommender.recommend("CP-2")).thenReturn(null);
        assertThat(service.recommend("CP-2")).isEmpty();
        verify(auditRepo, never()).save(any());
    }

    @Test
    void keepsValidActiveUnappliedLibraryCovenantsAndAuditsEach() {
        AppliedCovenant applied = mock(AppliedCovenant.class);
        CovenantMaster appliedMaster = master("9", AssessmentType.AUTO, true);
        when(applied.getCovenantMaster()).thenReturn(appliedMaster);
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of(applied));
        CovenantMaster m1 = master("1", AssessmentType.AUTO, true);
        CovenantMaster m2 = master("2", AssessmentType.AUTO, true);
        CovenantMaster m3 = master("3", AssessmentType.MANUAL, true);
        CovenantMaster m4 = master("4", AssessmentType.AUTO, false);
        when(masterRepo.findById("1")).thenReturn(Optional.of(m1));
        when(masterRepo.findById("2")).thenReturn(Optional.of(m2));
        when(masterRepo.findById("3")).thenReturn(Optional.of(m3));
        when(masterRepo.findById("4")).thenReturn(Optional.of(m4));
        when(masterRepo.findById("5")).thenReturn(Optional.empty());
        ExtractionRecommendation good = rec("1", "GOOD", 0.9);
        ExtractionRecommendation bad = rec("2", "BAD", null);
        ExtractionRecommendation manual = rec("3", null, 0.5);
        when(recommender.recommend("CP-1")).thenReturn(Arrays.asList(
                null, good, bad, manual, rec("4", "GOOD", 0.9), rec("5", "GOOD", 0.9), rec("9", "GOOD", 0.9)));

        assertThat(service.recommend("CP-1")).containsExactly(good, manual);

        ArgumentCaptor<ThresholdAudit> captor = ArgumentCaptor.forClass(ThresholdAudit.class);
        verify(auditRepo, times(3)).save(captor.capture());
        ThresholdAudit first = captor.getAllValues().get(0);
        assertThat(first.getSource()).isEqualTo(ThresholdSource.EXTRACTED);
        assertThat(first.getEvent()).isEqualTo(ThresholdAudit.Event.RECOMMENDED);
        assertThat(first.getNewExpression()).isEqualTo("GOOD");
        assertThat(first.getConfidence()).isEqualByComparingTo(new BigDecimal("0.9"));
        assertThat(captor.getAllValues().get(1).getConfidence()).isNull();
    }

    @Test
    void customCandidatesNeedANameAndKeepOnlyValidExpressions() {
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of());
        ExtractionRecommendation.CustomCovenant named =
                new ExtractionRecommendation.CustomCovenant("Capex", "def", "AUTO");
        ExtractionRecommendation valid = custom("GOOD", named);
        ExtractionRecommendation noExpr = custom(null, named);
        when(recommender.recommend("CP-1")).thenReturn(List.of(
                valid, noExpr, custom("BAD", named),
                custom("GOOD", null),
                custom("GOOD", new ExtractionRecommendation.CustomCovenant(" ", null, "AUTO")),
                custom("GOOD", new ExtractionRecommendation.CustomCovenant(null, null, "AUTO"))));

        List<ExtractionRecommendation> kept = service.recommend("CP-1");

        assertThat(kept).hasSize(3);
        assertThat(kept.get(0)).isEqualTo(valid);
        assertThat(kept.get(1)).isEqualTo(noExpr);
        assertThat(kept.get(2).recommendedExpression()).isNull();
        assertThat(kept.get(2).customCovenant()).isEqualTo(named);
        verify(auditRepo, never()).save(any());
    }

    @Test
    void blankOrMissingExpressionIsInvalidForAutoCovenant() {
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of());
        CovenantMaster m1 = master("1", AssessmentType.AUTO, true);
        CovenantMaster m2 = master("2", AssessmentType.AUTO, true);
        when(masterRepo.findById("1")).thenReturn(Optional.of(m1));
        when(masterRepo.findById("2")).thenReturn(Optional.of(m2));
        when(recommender.recommend("CP-1")).thenReturn(List.of(rec("1", " ", 0.9), rec("2", null, 0.9)));
        assertThat(service.recommend("CP-1")).isEmpty();
    }

    private static ExtractionRecommendation rec(String id, String expr, Double confidence) {
        return new ExtractionRecommendation(id, expr, confidence, "r", null, null, null);
    }

    private static ExtractionRecommendation custom(String expr, ExtractionRecommendation.CustomCovenant c) {
        return new ExtractionRecommendation(null, expr, 0.7, "r", null, null, c);
    }

    private static CovenantMaster master(String id, AssessmentType type, boolean active) {
        CovenantMaster m = mock(CovenantMaster.class);
        lenient().when(m.getId()).thenReturn(id);
        lenient().when(m.getConditionExpression()).thenReturn("BASE");
        lenient().when(m.getAssessmentType()).thenReturn(type);
        lenient().when(m.isActive()).thenReturn(active);
        return m;
    }
}
