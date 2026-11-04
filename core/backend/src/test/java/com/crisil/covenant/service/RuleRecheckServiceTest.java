package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.RuleRecheck;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentTrigger;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RuleRecheckServiceTest {

    @Mock private AppliedCovenantRepository appliedRepo;
    @Mock private AssessmentService assessmentService;

    private static AppliedCovenant applied(String id, String own) {
        AppliedCovenant ac = new AppliedCovenant();
        ReflectionTestUtils.setField(ac, "id", id);
        ac.setConditionExpression(own);
        return ac;
    }

    private static Assessment result(AssessmentResult r) {
        Assessment a = new Assessment();
        a.setResult(r);
        return a;
    }

    @Test
    void rechecksCpsFollowingTheRuleAndCountsOutcomes() {
        when(appliedRepo.findByCovenantMasterIdAndStatusOrderByIdAsc("m1", AppliedCovenantStatus.ACTIVE))
                .thenReturn(List.of(applied("a1", null), applied("a2", " "), applied("a3", "DSCR > 2"),
                        applied("a4", null), applied("a5", null)));
        when(assessmentService.runAssessment("a1", AssessmentTrigger.MANUAL, "author-1"))
                .thenReturn(result(AssessmentResult.BREACHED));
        when(assessmentService.runAssessment("a2", AssessmentTrigger.MANUAL, "author-1"))
                .thenReturn(result(AssessmentResult.COMPLIANT));
        when(assessmentService.runAssessment("a4", AssessmentTrigger.MANUAL, "author-1"))
                .thenReturn(result(AssessmentResult.ERROR));
        when(assessmentService.runAssessment("a5", AssessmentTrigger.MANUAL, "author-1"))
                .thenThrow(new IllegalStateException("source down"));

        RuleRecheck out = new RuleRecheckService(appliedRepo, assessmentService).recheck("m1", "author-1");

        assertThat(out).isEqualTo(new RuleRecheck(4, 1, 2));
        // a3 has its own threshold, so the library change does not touch it.
        verify(assessmentService, never()).runAssessment("a3", AssessmentTrigger.MANUAL, "author-1");
    }
}
