package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.ThresholdSource;
import com.crisil.covenant.entity.ThresholdAudit;
import com.crisil.covenant.repository.CovenantMasterRepository;
import com.crisil.covenant.repository.ThresholdAuditRepository;
import java.util.Optional;
import com.crisil.covenant.service.CachedCovenantAttributesService;
import com.crisil.covenant.domain.CovenantAttributes;
import com.crisil.covenant.formula.CompiledFormula;
import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.formula.FormulaValidationResult;
import com.crisil.covenant.formula.MeasureCatalog;
import com.crisil.covenant.service.MeasureCatalogService;
import com.crisil.covenant.dto.MlCovenantContext;
import com.crisil.covenant.dto.MlThresholdRecommendation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MlThresholdServiceTest {

    @Mock private AppliedCovenantRepository appliedRepo;
    @Mock private CovenantMasterRepository masterRepo;
    @Mock private CachedCovenantAttributesService attributesService;
    @Mock private MlThresholdRecommender recommender;
    @Mock private ThresholdAuditRepository auditRepo;
    @Mock private FormulaEngine formulaEngine;
    @Mock private MeasureCatalogService measureCatalog;

    @InjectMocks private MlThresholdService service;

    @BeforeEach
    void stubContext() {
        lenient().when(attributesService.fetch("CP-1")).thenReturn(
                new CovenantAttributes("CP-1", "SEG", "SECTOR", Map.of("rating", "AA")));
        lenient().when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of());
        lenient().when(measureCatalog.activeCatalog()).thenReturn(mock(MeasureCatalog.class));
    }

    private void stubSameMeasures(String recommended, String current, boolean valid, String measure) {
        when(formulaEngine.validate(eq(recommended), any(), anyBoolean()))
                .thenReturn(new FormulaValidationResult(valid, List.of(), "x", "true"));
        if (valid) {
            // Build stub mocks first; compiledWith() calls when(), which must not be nested inside
            // another when(...).thenReturn(...) (Mockito UnfinishedStubbing).
            CompiledFormula recCompiled = compiledWith(measure);
            CompiledFormula curCompiled = compiledWith(measure);
            when(formulaEngine.compile(eq(recommended), any())).thenReturn(recCompiled);
            when(formulaEngine.compile(eq(current), any())).thenReturn(curCompiled);
        }
    }

    @Test
    void auditsEveryRecommendationButSurfacesOnlyChangedValidOnes() {
        List<MlCovenantContext> scope = List.of(
                new MlCovenantContext("1", "DSCR", "DSCR > 1.5", null),
                new MlCovenantContext("2", "ICR", "ICR > 2", null));
        when(recommender.recommend(any())).thenReturn(List.of(
                rec("1", "DSCR > 1.6", "0.90", "tighter"),   // changed + valid → surfaced
                rec("2", "ICR > 2", "0.70", "unchanged")));  // equal to current → not surfaced
        stubSameMeasures("DSCR > 1.6", "DSCR > 1.5", true, "DSCR");

        List<MlThresholdRecommendation> out = service.recommend("CP-1", scope);

        assertThat(out).extracting(MlThresholdRecommendation::covenantMasterId).containsExactly("1");
        // both recommendations are audited (even the unchanged one)
        ArgumentCaptor<ThresholdAudit> captor = ArgumentCaptor.forClass(ThresholdAudit.class);
        verify(auditRepo, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(a -> {
            assertThat(a.getEvent()).isEqualTo(ThresholdAudit.Event.RECOMMENDED);
            assertThat(a.getSource()).isEqualTo(ThresholdSource.ML);
            assertThat(a.getCpId()).isEqualTo("CP-1");
            assertThat(a.getAppliedCovenantId()).isNull();
        });
        ThresholdAudit first = captor.getAllValues().get(0);
        assertThat(first.getOldExpression()).isEqualTo("DSCR > 1.5");
        assertThat(first.getNewExpression()).isEqualTo("DSCR > 1.6");
        assertThat(first.getConfidence()).isEqualByComparingTo("0.90");
        assertThat(first.getRationale()).isEqualTo("tighter");
    }

    @Test
    void ignoresRecommendationsOutsideTheInScopeSet() {
        List<MlCovenantContext> scope =
                List.of(new MlCovenantContext("1", "DSCR", "DSCR > 1.5", null));
        when(recommender.recommend(any())).thenReturn(List.of(
                rec("99", "OTHER > 9", "0.9", "off-scope"), // not requested → ignored entirely
                rec("1", "DSCR > 1.6", "0.9", "ok")));
        stubSameMeasures("DSCR > 1.6", "DSCR > 1.5", true, "DSCR");

        List<MlThresholdRecommendation> out = service.recommend("CP-1", scope);

        assertThat(out).extracting(MlThresholdRecommendation::covenantMasterId).containsExactly("1");
        // only the in-scope recommendation is audited
        verify(auditRepo, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void dropsInvalidRecommendationButStillAuditsIt() {
        List<MlCovenantContext> scope =
                List.of(new MlCovenantContext("1", "DSCR", "DSCR > 1.5", null));
        when(recommender.recommend(any())).thenReturn(List.of(rec("1", "DSCR >", "0.9", "bad")));
        when(formulaEngine.validate(eq("DSCR >"), any(), anyBoolean()))
                .thenReturn(new FormulaValidationResult(false, List.of(), null, null));

        List<MlThresholdRecommendation> out = service.recommend("CP-1", scope);

        assertThat(out).isEmpty();
        verify(auditRepo).save(any()); // recommendation still recorded for compliance
    }

    @Test
    void dropsRecommendationThatChangesMeasures() {
        List<MlCovenantContext> scope =
                List.of(new MlCovenantContext("1", "DSCR", "DSCR > 1.5", null));
        when(recommender.recommend(any())).thenReturn(List.of(rec("1", "ICR > 2", "0.9", "swap")));
        when(formulaEngine.validate(eq("ICR > 2"), any(), anyBoolean()))
                .thenReturn(new FormulaValidationResult(true, List.of(), "x", "true"));
        CompiledFormula icrCompiled = compiledWith("ICR");
        CompiledFormula dscrCompiled = compiledWith("DSCR");
        when(formulaEngine.compile(eq("ICR > 2"), any())).thenReturn(icrCompiled);
        when(formulaEngine.compile(eq("DSCR > 1.5"), any())).thenReturn(dscrCompiled);

        List<MlThresholdRecommendation> out = service.recommend("CP-1", scope);

        assertThat(out).isEmpty();
        verify(auditRepo).save(any());
    }

    @Test
    void emptyRecommendationsFromFallbackYieldEmptyListWithNoAudit() {
        List<MlCovenantContext> scope =
                List.of(new MlCovenantContext("1", "DSCR", "DSCR > 1.5", null));
        when(recommender.recommend(any())).thenReturn(List.of());

        assertThat(service.recommend("CP-1", scope)).isEmpty();
        verify(auditRepo, never()).save(any());
    }

    @Test
    void skipsNullRecommendationsAndBlankExpressions() {
        List<MlCovenantContext> scope =
                List.of(new MlCovenantContext("1", "DSCR", "DSCR > 1.5", null));
        java.util.List<MlThresholdRecommendation> recs = new java.util.ArrayList<>();
        recs.add(null);                                  // defensive: null entry ignored
        recs.add(rec("1", "  ", "0.9", "blank"));         // blank recommendation → audited, not surfaced
        when(recommender.recommend(any())).thenReturn(recs);

        List<MlThresholdRecommendation> out = service.recommend("CP-1", scope);

        assertThat(out).isEmpty();
        verify(auditRepo, org.mockito.Mockito.times(1)).save(any()); // only the non-null one
    }

    @Test
    void nullItemsHandledGracefully() {
        when(recommender.recommend(any())).thenReturn(List.of());
        assertThat(service.recommend("CP-1", null)).isEmpty();
    }

    @Test
    void sharesOnlyCpPayloadWithMlServiceNeverTheCovenantList() {
        // US-ML-20: the outbound ML request carries the CP's details only — never the in-scope or
        // already-applied covenant lists — even though in-scope covenants are passed to recommend(...).
        when(recommender.recommend(any())).thenReturn(List.of());

        service.recommend("CP-1", List.of(new MlCovenantContext("1", "DSCR", "DSCR > 1.5", null)));

        ArgumentCaptor<com.crisil.covenant.dto.MlThresholdRequest> captor =
                ArgumentCaptor.forClass(com.crisil.covenant.dto.MlThresholdRequest.class);
        verify(recommender).recommend(captor.capture());
        com.crisil.covenant.dto.MlThresholdRequest sent = captor.getValue();
        assertThat(sent.cpId()).isEqualTo("CP-1");
        assertThat(sent.cpAttributes())
                .containsEntry("rating", "AA")
                .containsEntry("businessSegment", "SEG")
                .containsEntry("sector", "SECTOR");
        assertThat(sent.covenants()).isEmpty();
        assertThat(sent.appliedContext()).isEmpty();
    }

    @Test
    void surfacesNewAutoCovenantNotInScopeAndAuditsAgainstMasterBaseline() {
        // ML introduces covenant "50" that the master (eligibility) stage did not include.
        when(recommender.recommend(any()))
                .thenReturn(List.of(rec("50", "DSCR > 1.6", "0.9", "add DSCR")));
        CovenantMaster m50 = master("50", "DSCR > 1.5", AssessmentType.AUTO, true);
        when(masterRepo.findById("50")).thenReturn(Optional.of(m50));
        stubSameMeasures("DSCR > 1.6", "DSCR > 1.5", true, "DSCR");

        List<MlThresholdRecommendation> out = service.recommend("CP-1", List.of());

        assertThat(out).extracting(MlThresholdRecommendation::covenantMasterId).containsExactly("50");
        ArgumentCaptor<ThresholdAudit> captor = ArgumentCaptor.forClass(ThresholdAudit.class);
        verify(auditRepo).save(captor.capture());
        // Baseline for a new covenant is the master's own condition.
        assertThat(captor.getValue().getOldExpression()).isEqualTo("DSCR > 1.5");
        assertThat(captor.getValue().getNewExpression()).isEqualTo("DSCR > 1.6");
    }

    @Test
    void surfacesNewManualCovenantWithoutThresholdValidation() {
        when(recommender.recommend(any()))
                .thenReturn(List.of(rec("60", "Board approval on file", "0.9", "add clause")));
        CovenantMaster m60 = master("60", "Board approval on file", AssessmentType.MANUAL, true);
        when(masterRepo.findById("60")).thenReturn(Optional.of(m60));

        List<MlThresholdRecommendation> out = service.recommend("CP-1", List.of());

        assertThat(out).extracting(MlThresholdRecommendation::covenantMasterId).containsExactly("60");
        verify(auditRepo).save(any()); // still audited
        verify(formulaEngine, never()).validate(any(), any(), anyBoolean()); // no threshold to validate
    }

    @Test
    void ignoresNewCandidateThatIsInactiveOrUnknown() {
        when(recommender.recommend(any())).thenReturn(List.of(
                rec("70", "DSCR > 1.6", "0.9", "inactive"),
                rec("80", "DSCR > 1.6", "0.9", "unknown")));
        CovenantMaster m70 = master("70", "DSCR > 1.5", AssessmentType.AUTO, false);
        when(masterRepo.findById("70")).thenReturn(Optional.of(m70));
        when(masterRepo.findById("80")).thenReturn(Optional.empty());

        List<MlThresholdRecommendation> out = service.recommend("CP-1", List.of());

        assertThat(out).isEmpty();
        verify(auditRepo, never()).save(any()); // neither an active master → nothing recorded
    }

    private static MlThresholdRecommendation rec(String id, String expr, String confidence, String rationale) {
        return new MlThresholdRecommendation(id, expr, new BigDecimal(confidence), rationale);
    }

    private static CovenantMaster master(String id, String expression, AssessmentType type,
                                         boolean active) {
        CovenantMaster m = mock(CovenantMaster.class);
        lenient().when(m.getId()).thenReturn(id);
        lenient().when(m.getConditionExpression()).thenReturn(expression);
        lenient().when(m.getAssessmentType()).thenReturn(type);
        lenient().when(m.isActive()).thenReturn(active);
        return m;
    }

    private static CompiledFormula compiledWith(String... measures) {
        CompiledFormula c = mock(CompiledFormula.class);
        when(c.referencedMeasures()).thenReturn(List.of(measures));
        return c;
    }
}
