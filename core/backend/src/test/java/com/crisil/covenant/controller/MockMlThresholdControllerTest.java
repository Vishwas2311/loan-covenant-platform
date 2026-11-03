package com.crisil.covenant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.dto.MlCovenantContext;
import com.crisil.covenant.dto.MlThresholdRecommendation;
import com.crisil.covenant.dto.MlThresholdRequest;
import com.crisil.covenant.repository.CovenantMasterRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MockMlThresholdControllerTest {

    private final CovenantMasterRepository masterRepo = mock(CovenantMasterRepository.class);
    private final MockMlThresholdController controller =
            new MockMlThresholdController(new FormulaEngine(), masterRepo);

    private MlThresholdRecommendation recommendOne(String id, String expression) {
        MlThresholdRequest request = new MlThresholdRequest(
                "CP-1", Map.of(), List.of(new MlCovenantContext(id, "cov", expression, null)), List.of());
        List<MlThresholdRecommendation> out = controller.recommend(request);
        assertThat(out).hasSize(1);
        return out.get(0);
    }

    @Test
    void tightensGreaterThanNumericThresholdByFivePercent() {
        MlThresholdRecommendation rec = recommendOne("1", "DSCR > 1.5");
        // 1.5 * 1.05 = 1.575
        assertThat(rec.recommendedExpression()).isEqualTo("DSCR > 1.575");
        assertThat(rec.covenantMasterId()).isEqualTo("1");
        assertThat(rec.rationale()).contains("Adjusted 1 threshold");
    }

    @Test
    void loosensLessThanNumericThresholdByFivePercent() {
        // 4 * 0.95 = 3.80 → stripTrailingZeros → 3.8
        assertThat(recommendOne("2", "leverage < 4").recommendedExpression())
                .isEqualTo("leverage < 3.8");
    }

    @Test
    void tightensEachThresholdInAConjunction() {
        // 1.5 * 1.05 = 1.575 ; 2 * 1.05 = 2.10 → 2.1
        assertThat(recommendOne("3", "DSCR > 1.5 AND ICR > 2").recommendedExpression())
                .isEqualTo("DSCR > 1.575 AND ICR > 2.1");
    }

    @Test
    void leavesBooleanThresholdUnchanged() {
        MlThresholdRecommendation rec = recommendOne("4", "insuranceActive == TRUE");
        assertThat(rec.recommendedExpression()).isEqualTo("insuranceActive == TRUE");
        assertThat(rec.rationale()).contains("No numeric thresholds");
    }

    @Test
    void leavesDateThresholdUnchanged() {
        String expr = "LATEST(lastAuditDate, '1y') > DATE('2026-01-01')";
        assertThat(recommendOne("5", expr).recommendedExpression()).isEqualTo(expr);
    }

    @Test
    void leavesEqualityMeasureThresholdUnchanged() {
        // '==' is neither tighten nor loosen → skipped, expression unchanged.
        assertThat(recommendOne("6", "auditor == 'PwC'").recommendedExpression())
                .isEqualTo("auditor == 'PwC'");
    }

    @Test
    void unparseableExpressionYieldsNoChange() {
        assertThat(recommendOne("7", "DSCR >").recommendedExpression()).isEqualTo("DSCR >");
    }

    @Test
    void roundsRepeatingProductToFourDecimals() {
        // 1.33 * 1.05 = 1.3965 (already 4dp); 1.333 * 1.05 = 1.39965 → HALF_UP to 1.3997 (would be >4dp)
        assertThat(recommendOne("8", "DSCR > 1.333").recommendedExpression())
                .isEqualTo("DSCR > 1.3997");
    }

    @Test
    void tightensPercentThresholdPreservingSuffix() {
        // 50 * 1.05 = 52.50 → 52.5, percent suffix preserved
        assertThat(recommendOne("9", "ratio > 50%").recommendedExpression())
                .isEqualTo("ratio > 52.5%");
    }

    @Test
    void confidenceIsDeterministicWithinExpectedRange() {
        BigDecimal confidence = recommendOne("1", "DSCR > 1.5").confidence();
        // id "1".hashCode()==49; 0.80 + floorMod(49,20)*0.01 = 0.89
        assertThat(confidence).isEqualByComparingTo("0.89");
        assertThat(confidence).isBetween(new BigDecimal("0.80"), new BigDecimal("0.99"));
    }

    @Test
    void emptyCovenantListYieldsNoRecommendations() {
        MlThresholdRequest request = new MlThresholdRequest("CP-1", Map.of(), List.of(), List.of());
        assertThat(controller.recommend(request)).isEmpty();
    }

    @Test
    void nullCovenantListYieldsNoRecommendations() {
        MlThresholdRequest request = new MlThresholdRequest("CP-1", Map.of(), null, List.of());
        assertThat(controller.recommend(request)).isEmpty();
    }

    @Test
    void suggestsUpToTwoNewLibraryCovenantsNotInPlay() {
        // Eligible masters (active, non-custom, not in play): one AUTO (nudged), one MANUAL (as-is).
        // The inactive, custom and in-play masters must be skipped.
        List<CovenantMaster> masters = List.of(
                master("m-auto", "DSCR > 2", AssessmentType.AUTO, true, false),
                master("m-manual", "insuranceActive == TRUE", AssessmentType.MANUAL, true, false),
                master("m-inactive", "leverage < 5", AssessmentType.AUTO, false, false),
                master("m-custom", "ICR > 3", AssessmentType.AUTO, true, true),
                master("in-scope", "DSCR > 1.5", AssessmentType.AUTO, true, false));
        when(masterRepo.findAll()).thenReturn(masters);

        MlThresholdRequest request = new MlThresholdRequest(
                "CP-1", Map.of(),
                List.of(new MlCovenantContext("in-scope", "cov", "DSCR > 1.5", null)),
                List.of());
        List<MlThresholdRecommendation> out = controller.recommend(request);

        // 1 echo/nudge for the in-scope covenant + 2 new-covenant suggestions.
        assertThat(out).hasSize(3);
        assertThat(out.get(0).covenantMasterId()).isEqualTo("in-scope");
        assertThat(out.get(0).recommendedExpression()).isEqualTo("DSCR > 1.575");

        MlThresholdRecommendation auto = out.stream()
                .filter(r -> r.covenantMasterId().equals("m-auto")).findFirst().orElseThrow();
        // 2 * 1.05 = 2.10 -> 2.1 (nudged master threshold).
        assertThat(auto.recommendedExpression()).isEqualTo("DSCR > 2.1");
        assertThat(auto.rationale()).contains("calibrated to the CP profile");

        MlThresholdRecommendation manual = out.stream()
                .filter(r -> r.covenantMasterId().equals("m-manual")).findFirst().orElseThrow();
        assertThat(manual.recommendedExpression()).isEqualTo("insuranceActive == TRUE");
        assertThat(manual.rationale()).contains("recommend adding it");
    }

    @Test
    void doesNotSuggestCovenantsAlreadyInAppliedContext() {
        List<CovenantMaster> masters = List.of(
                master("applied-lib", "DSCR > 2", AssessmentType.AUTO, true, false));
        when(masterRepo.findAll()).thenReturn(masters);

        MlThresholdRequest request = new MlThresholdRequest(
                "CP-1", Map.of(),
                List.of(new MlCovenantContext("in-scope", "cov", "DSCR > 1.5", null)),
                List.of(new MlCovenantContext("applied-lib", "cov2", "DSCR > 2", null)));
        List<MlThresholdRecommendation> out = controller.recommend(request);

        // Only the in-scope echo; the applied-context master is not re-suggested.
        assertThat(out).hasSize(1);
        assertThat(out.get(0).covenantMasterId()).isEqualTo("in-scope");
    }

    private static CovenantMaster master(String id, String expression, AssessmentType type,
                                         boolean active, boolean custom) {
        CovenantMaster m = mock(CovenantMaster.class);
        lenient().when(m.getId()).thenReturn(id);
        lenient().when(m.getConditionExpression()).thenReturn(expression);
        lenient().when(m.getAssessmentType()).thenReturn(type);
        lenient().when(m.isActive()).thenReturn(active);
        lenient().when(m.isCustom()).thenReturn(custom);
        return m;
    }
}
