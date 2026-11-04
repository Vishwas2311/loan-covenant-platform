package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RuleRecheckServiceTest {

    @Mock private AppliedCovenantRepository appliedRepo;
    @Mock private AssessmentService assessmentService;

    /** Runs queued work when asked, so the test can see what was queued before it runs. */
    private final List<Runnable> queued = new ArrayList<>();
    private final TaskExecutor executor = queued::add;

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
    void queuesCpsFollowingTheRuleThenChecksAndCountsThem() {
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
                .thenThrow(new IllegalStateException("source\ndown"));
        RuleRecheckService svc = new RuleRecheckService(appliedRepo, assessmentService, executor);

        assertThat(svc.start("m1", "author-1")).isEqualTo(new RuleRecheck(4));
        // Nothing is checked until the background task runs.
        verify(assessmentService, never()).runAssessment(any(), any(), any());
        assertThat(queued).hasSize(1);
        queued.get(0).run();

        assertThat(svc.run(List.of("a1", "a2", "a4", "a5"), "author-1")).containsExactly(4, 1, 2);
        // a3 has its own threshold, so the library change does not touch it.
        verify(assessmentService, never()).runAssessment("a3", AssessmentTrigger.MANUAL, "author-1");
    }

    @Test
    void nothingToQueueWhenNoCpFollowsTheRule() {
        when(appliedRepo.findByCovenantMasterIdAndStatusOrderByIdAsc("m2", AppliedCovenantStatus.ACTIVE))
                .thenReturn(List.of(applied("a9", "DSCR > 2")));
        RuleRecheckService svc = new RuleRecheckService(appliedRepo, assessmentService, executor);
        assertThat(svc.start("m2", "author-1").queued()).isZero();
        assertThat(queued).isEmpty();
    }
}
