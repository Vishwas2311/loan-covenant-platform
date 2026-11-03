package com.crisil.covenant.controller;

import com.crisil.covenant.security.CpAccessGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.dto.AssessmentResponse;
import com.crisil.covenant.dto.CompleteManualAssessmentRequest;
import com.crisil.covenant.entity.CovenantMaster;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentStatus;
import com.crisil.covenant.enums.AssessmentTrigger;
import com.crisil.covenant.service.AssessmentService;

@ExtendWith(MockitoExtension.class)
class AssessmentControllerTest {

    @Mock
    private AssessmentService service;

    @Mock
    private CpAccessGuard guard;

    @InjectMocks
    private AssessmentController controller;

    private static Assessment sample(AssessmentStatus status, AssessmentResult result) {
        CovenantMaster master = new CovenantMaster();
        master.setName("Rule");
        AppliedCovenant applied = new AppliedCovenant();
        applied.setCpId("CP-1");
        applied.setCovenantMaster(master);

        Assessment a = new Assessment();
        a.setAppliedCovenant(applied);
        a.setScheduledAt(Instant.parse("2026-06-04T10:00:00Z"));
        a.setStatus(status);
        a.setResult(result);
        return a;
    }

    @Test
    void runManualDelegates() {
        when(service.runAssessment(eq("1"), eq(AssessmentTrigger.MANUAL), any()))
                .thenReturn(sample(AssessmentStatus.COMPLETED, AssessmentResult.COMPLIANT));

        AssessmentResponse response = controller.runManual("1");

        assertThat(response.result()).isEqualTo(AssessmentResult.COMPLIANT);
        verify(service).runAssessment(eq("1"), eq(AssessmentTrigger.MANUAL), any());
    }

    @Test
    void listForCpDelegates() {
        when(service.listForCp("CP-1"))
                .thenReturn(List.of(sample(AssessmentStatus.PENDING_MANUAL, AssessmentResult.PENDING_REVIEW)));
        assertThat(controller.listForCp("CP-1")).hasSize(1);
    }

    @Test
    void getDelegates() {
        when(service.get("5")).thenReturn(sample(AssessmentStatus.COMPLETED, AssessmentResult.BREACHED));
        assertThat(controller.get("5").result()).isEqualTo(AssessmentResult.BREACHED);
    }

    @Test
    void completeManualDelegatesResultAndNote() {
        Assessment completed = sample(AssessmentStatus.COMPLETED, AssessmentResult.COMPLIANT);
        completed.setAssessmentNote("Verified");
        when(service.completeManual(eq("5"), eq(AssessmentResult.COMPLIANT), eq("Verified"), any()))
                .thenReturn(completed);

        CompleteManualAssessmentRequest request =
                new CompleteManualAssessmentRequest(AssessmentResult.COMPLIANT, "Verified");
        AssessmentResponse response = controller.completeManual("5", request);

        assertThat(response.status()).isEqualTo(AssessmentStatus.COMPLETED);
        assertThat(response.assessmentNote()).isEqualTo("Verified");
    }
}
