package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.domain.CpUser;
import com.crisil.covenant.domain.CreditProposalDetails;
import com.crisil.covenant.domain.WorkflowCase;
import com.crisil.covenant.dto.WorkflowCaseRequest;
import com.crisil.covenant.entity.CreditProposalCacheEntry;
import com.crisil.covenant.exception.WorkflowException;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import com.crisil.covenant.service.WorkflowCaseContext.CaseOutcome;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowCaseServiceTest {

    @Mock private WorkflowClient workflowClient;
    @Mock private CreditProposalCacheRepository cacheRepo;
    @Mock private CreditProposalClient creditProposalClient;
    @InjectMocks private WorkflowCaseService service;

    @Test
    void cacheHitUsesCachedRmAndCreatesCaseWithThreePlaceholders() {
        when(cacheRepo.findById("CP-1")).thenReturn(Optional.of(cacheEntry("CP-1", "rm-anil")));
        when(workflowClient.startCase(any())).thenReturn(new WorkflowCase("case-1"));

        CaseOutcome out = service.ensureCase("CP-1", service.newRunContext());

        assertThat(out.caseId()).isEqualTo("case-1");
        assertThat(out.error()).isNull();

        ArgumentCaptor<WorkflowCaseRequest> captor = ArgumentCaptor.forClass(WorkflowCaseRequest.class);
        verify(workflowClient).startCase(captor.capture());
        assertThat(captor.getValue().workflowCode()).isEqualTo("Covenant_Monitoring");
        assertThat(captor.getValue().variables().manualAssigneee()).isEqualTo("rm-anil");

        verify(workflowClient).submitCaseStep1("case-1");
        verify(workflowClient).submitCaseStep2("case-1");
        verify(workflowClient).submitCaseStep3("case-1");
    }

    @Test
    void cacheMissFallsBackToLookupForRm() {
        when(cacheRepo.findById("CP-1")).thenReturn(Optional.empty());
        when(creditProposalClient.lookup("CP-1"))
                .thenReturn(detailsWithRm(new CpUser("rm-priya", "Priya", "p@x")));
        when(workflowClient.startCase(any())).thenReturn(new WorkflowCase("case-2"));

        CaseOutcome out = service.ensureCase("CP-1", service.newRunContext());

        assertThat(out.caseId()).isEqualTo("case-2");
        assertThat(requestAssignee()).isEqualTo("rm-priya");
    }

    @Test
    void blankCachedRmFallsBackToLookup() {
        when(cacheRepo.findById("CP-1")).thenReturn(Optional.of(cacheEntry("CP-1", "  ")));
        when(creditProposalClient.lookup("CP-1"))
                .thenReturn(detailsWithRm(new CpUser("rm-priya", "Priya", "p@x")));
        when(workflowClient.startCase(any())).thenReturn(new WorkflowCase("case-3"));

        CaseOutcome out = service.ensureCase("CP-1", service.newRunContext());

        assertThat(out.caseId()).isEqualTo("case-3");
        assertThat(requestAssignee()).isEqualTo("rm-priya");
    }

    @Test
    void nullLookupStillCreatesCaseWithNoAssignee() {
        when(cacheRepo.findById("CP-1")).thenReturn(Optional.empty());
        when(creditProposalClient.lookup("CP-1")).thenReturn(null);
        when(workflowClient.startCase(any())).thenReturn(new WorkflowCase("case-4"));

        CaseOutcome out = service.ensureCase("CP-1", service.newRunContext());

        assertThat(out.caseId()).isEqualTo("case-4");
        assertThat(requestAssignee()).isNull();
    }

    @Test
    void lookupWithNoRmStillCreatesCase() {
        when(cacheRepo.findById("CP-1")).thenReturn(Optional.empty());
        when(creditProposalClient.lookup("CP-1")).thenReturn(detailsWithRm(null));
        when(workflowClient.startCase(any())).thenReturn(new WorkflowCase("case-5"));

        CaseOutcome out = service.ensureCase("CP-1", service.newRunContext());

        assertThat(out.caseId()).isEqualTo("case-5");
        assertThat(requestAssignee()).isNull();
    }

    @Test
    void lookupFailureIsNonFatalAndCaseStillCreated() {
        when(cacheRepo.findById("CP-1")).thenReturn(Optional.empty());
        when(creditProposalClient.lookup("CP-1")).thenThrow(new RuntimeException("cp service down"));
        when(workflowClient.startCase(any())).thenReturn(new WorkflowCase("case-6"));

        CaseOutcome out = service.ensureCase("CP-1", service.newRunContext());

        assertThat(out.caseId()).isEqualTo("case-6");
        assertThat(out.error()).isNull();
        assertThat(requestAssignee()).isNull();
    }

    @Test
    void nullBodyFromStartCaseMarksErrorAndSkipsPlaceholders() {
        when(cacheRepo.findById("CP-1")).thenReturn(Optional.of(cacheEntry("CP-1", "rm-anil")));
        when(workflowClient.startCase(any())).thenReturn(null);

        CaseOutcome out = service.ensureCase("CP-1", service.newRunContext());

        assertThat(out.caseId()).isNull();
        assertThat(out.error()).contains("empty body");
        verify(workflowClient, never()).submitCaseStep1(anyString());
        verify(workflowClient, never()).submitCaseStep2(anyString());
        verify(workflowClient, never()).submitCaseStep3(anyString());
    }

    @Test
    void blankCaseIdFromStartCaseMarksError() {
        when(cacheRepo.findById("CP-1")).thenReturn(Optional.of(cacheEntry("CP-1", "rm-anil")));
        when(workflowClient.startCase(any())).thenReturn(new WorkflowCase("   "));

        CaseOutcome out = service.ensureCase("CP-1", service.newRunContext());

        assertThat(out.caseId()).isNull();
        assertThat(out.error()).contains("empty body");
    }

    @Test
    void startCaseFailureMarksErrorAndSkipsPlaceholders() {
        when(cacheRepo.findById("CP-1")).thenReturn(Optional.of(cacheEntry("CP-1", "rm-anil")));
        when(workflowClient.startCase(any()))
                .thenThrow(new WorkflowException("engine down", new RuntimeException()));

        CaseOutcome out = service.ensureCase("CP-1", service.newRunContext());

        assertThat(out.caseId()).isNull();
        assertThat(out.error()).contains("engine down");
        verify(workflowClient, never()).submitCaseStep1(anyString());
    }

    @Test
    void downstreamStepFailureDoesNotLoseCaseId() {
        when(cacheRepo.findById("CP-1")).thenReturn(Optional.of(cacheEntry("CP-1", "rm-anil")));
        when(workflowClient.startCase(any())).thenReturn(new WorkflowCase("case-9"));
        doThrow(new RuntimeException("step boom")).when(workflowClient).submitCaseStep1("case-9");

        CaseOutcome out = service.ensureCase("CP-1", service.newRunContext());

        // The placeholder failure is swallowed; we must still keep the caseId already obtained.
        assertThat(out.caseId()).isEqualTo("case-9");
        assertThat(out.error()).isNull();
    }

    @Test
    void memoisesCasePerCpWithinRunButOpensNewCaseForAnotherCp() {
        when(cacheRepo.findById(anyString())).thenReturn(Optional.of(cacheEntry("CP", "rm-anil")));
        when(workflowClient.startCase(any())).thenReturn(new WorkflowCase("case-1"));

        WorkflowCaseContext ctx = service.newRunContext();
        CaseOutcome first = service.ensureCase("CP-1", ctx);
        CaseOutcome second = service.ensureCase("CP-1", ctx);

        // One case per CP per run: the second request for the same CP is memoised, not re-created.
        assertThat(second).isSameAs(first);
        verify(workflowClient, times(1)).startCase(any());

        // A different CP in the same run opens its own case.
        service.ensureCase("CP-2", ctx);
        verify(workflowClient, times(2)).startCase(any());
    }

    private static CreditProposalCacheEntry cacheEntry(String cpId, String rmUserId) {
        CreditProposalCacheEntry entry = new CreditProposalCacheEntry();
        entry.setCpId(cpId);
        entry.setRmUserId(rmUserId);
        return entry;
    }

    private static CreditProposalDetails detailsWithRm(CpUser rm) {
        return new CreditProposalDetails("CP-1", "Acme", null, null, null, null, rm, null, null, null);
    }

    /** The {@code variables.manualAssigneee} of the single captured startCase request. */
    private String requestAssignee() {
        ArgumentCaptor<WorkflowCaseRequest> captor = ArgumentCaptor.forClass(WorkflowCaseRequest.class);
        verify(workflowClient).startCase(captor.capture());
        return captor.getValue().variables().manualAssigneee();
    }
}
