package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.domain.CpUser;
import com.crisil.covenant.domain.CreditProposalDetails;
import com.crisil.covenant.dto.AttachmentView;
import com.crisil.covenant.dto.CertificateRequirementView;
import com.crisil.covenant.dto.UpdateAppliedCovenantRequest;
import com.crisil.covenant.entity.AccessKey;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.entity.CreditProposalCacheEntry;
import com.crisil.covenant.entity.LoanSyncChange;
import com.crisil.covenant.entity.LoanSyncRun;
import com.crisil.covenant.entity.PortalAccess;
import com.crisil.covenant.entity.PortalSubmission;
import com.crisil.covenant.entity.WorkTask;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import com.crisil.covenant.repository.LoanSyncChangeRepository;
import com.crisil.covenant.repository.LoanSyncRunRepository;
import com.crisil.covenant.repository.PortalAccessRepository;
import com.crisil.covenant.repository.PortalSubmissionRepository;
import com.crisil.covenant.repository.WorkTaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

/** C1 borrower portal and C8 loan-system sync. */
@ExtendWith(MockitoExtension.class)
class Phase4ServicesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T06:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    @Mock private PortalAccessRepository access;
    @Mock private PortalSubmissionRepository submissions;
    @Mock private CertificateRequirementService requirements;
    @Mock private AttachmentService attachments;
    @Mock private AppliedCovenantRepository appliedRepo;
    @Mock private CreditProposalClient creditProposals;
    @Mock private CreditProposalCacheRepository cacheRepo;
    @Mock private AppliedCovenantService appliedService;
    @Mock private WorkTaskRepository tasks;
    @Mock private LoanSyncRunRepository runs;
    @Mock private LoanSyncChangeRepository changes;

    private PortalService portal() {
        return new PortalService(access, submissions, requirements, attachments, appliedRepo, creditProposals,
                mock(WebClient.class), CLOCK);
    }

    private static CertificateRequirementView requirement(LocalDate period, CertificateRequirementView.State state) {
        return new CertificateRequirementView("q-" + period, "CP-1003", period, period.plusDays(45), null, state, 0,
                List.of());
    }

    private static AppliedCovenant applied(String id, AppliedCovenantStatus status) {
        CovenantMaster m = new CovenantMaster();
        m.setName("Minimum DSCR");
        AppliedCovenant a = new AppliedCovenant();
        try {
            var f = AppliedCovenant.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(a, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        a.setCovenantMaster(m);
        a.setStatus(status);
        return a;
    }

    // ---------------------------------------------------------------- C1

    @Test
    void aBorrowerSeesOnlyTheirCpsAndWhatIsDue() {
        when(access.findByUser("borrower-reliance")).thenReturn(List.of(
                new PortalAccess("CP-1003", "borrower-reliance", "rm-anil", Instant.now(CLOCK))));
        when(creditProposals.lookup("CP-1003")).thenReturn(new CreditProposalDetails("CP-1003", "Reliance", null, null,
                "APPROVED", null, null, null, null, null));
        assertThat(portal().cpsFor("borrower-reliance")).containsExactly(new PortalService.PortalCp("CP-1003", "Reliance"));

        when(access.existsById(new AccessKey("CP-1003", "borrower-reliance"))).thenReturn(true);
        when(requirements.forCp("CP-1003")).thenReturn(List.of(
                requirement(LocalDate.of(2026, 9, 30), CertificateRequirementView.State.PENDING)));
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1003")).thenReturn(List.of(
                applied("a1", AppliedCovenantStatus.ACTIVE), applied("a2", AppliedCovenantStatus.SUSPENDED)));
        when(submissions.findByCpIdOrderBySubmittedAtDesc("CP-1003")).thenReturn(List.of());

        PortalService.PortalView v = portal().view("borrower-reliance", "CP-1003");
        assertThat(v.requirements()).singleElement().satisfies(r -> assertThat(r.state()).isEqualTo("PENDING"));
        assertThat(v.covenants()).hasSize(1);

        when(access.existsById(new AccessKey("CP-1001", "borrower-reliance"))).thenReturn(false);
        assertThatThrownBy(() -> portal().view("borrower-reliance", "CP-1001"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Not found");
        assertThatThrownBy(() -> portal().view(null, "CP-1003")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void aSignedCertificateIsStoredForItsPeriodAndRecorded() {
        when(access.existsById(new AccessKey("CP-1003", "b"))).thenReturn(true);
        LocalDate q3 = LocalDate.of(2026, 9, 30);
        when(requirements.forCp("CP-1003")).thenReturn(List.of(requirement(q3, CertificateRequirementView.State.PENDING),
                requirement(LocalDate.of(2026, 6, 30), CertificateRequirementView.State.RECEIVED)));
        when(attachments.addCertificates(eq("CP-1003"), anyList(), eq(q3))).thenReturn(List.of(
                new AttachmentView("att-1", "f1", "cert.pdf", "b", Instant.now(CLOCK))));
        lenient().when(appliedRepo.findByCpIdOrderByIdAsc("CP-1003")).thenReturn(List.of());
        MockMultipartFile file = new MockMultipartFile("file", "C:\\docs\\cert.pdf", "application/pdf", new byte[] {1});

        portal().submitCertificate("b", "CP-1003", file, q3, " Priya Rao, CFO ", true);

        ArgumentCaptor<PortalSubmission> saved = ArgumentCaptor.forClass(PortalSubmission.class);
        verify(submissions).save(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo("CERTIFICATE");
        assertThat(saved.getValue().getFileName()).isEqualTo("cert.pdf");
        assertThat(saved.getValue().getNote()).isEqualTo("Signed by Priya Rao, CFO");
        assertThat(saved.getValue().getRefId()).isEqualTo("att-1");

        assertThatThrownBy(() -> portal().submitCertificate("b", "CP-1003", file, q3, "Priya", false))
                .hasMessageContaining("Confirm");
        assertThatThrownBy(() -> portal().submitCertificate("b", "CP-1003", file, LocalDate.of(2026, 6, 30), "Priya", true))
                .hasMessageContaining("No certificate is waiting");
        assertThatThrownBy(() -> portal().submitCertificate("b", "CP-1003",
                new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[0]), q3, "Priya", true))
                .hasMessageContaining("Choose a file");
    }

    @Test
    void aStatementNeedsAPastPeriodEndAndAFile() {
        when(access.existsById(new AccessKey("CP-1003", "b"))).thenReturn(true);
        MockMultipartFile file = new MockMultipartFile("file", "q3.csv", "text/csv", "EBITDA,1".getBytes());
        assertThatThrownBy(() -> portal().submitStatement("b", "CP-1003", file, TODAY.plusDays(1)))
                .hasMessageContaining("period end");
        assertThatThrownBy(() -> portal().submitStatement("b", "CP-1003", file, null)).hasMessageContaining("period end");
        verify(submissions, never()).save(any());
    }

    @Test
    void theBankGrantsAndRevokesPortalAccess() {
        when(access.existsById(new AccessKey("CP-1003", "borrower-reliance"))).thenReturn(false);
        when(access.findByCp("CP-1003")).thenReturn(List.of());
        portal().grant("CP-1003", " borrower-reliance ", "rm-anil");
        verify(access).save(any(PortalAccess.class));
        assertThatThrownBy(() -> portal().grant("CP-1003", "x y", "rm-anil")).hasMessageContaining("valid user id");
        assertThatThrownBy(() -> portal().grant("CP-1003", null, "rm-anil")).hasMessageContaining("valid user id");
        portal().revoke("CP-1003", "borrower-reliance");
        verify(access).deleteById(new AccessKey("CP-1003", "borrower-reliance"));
        assertThat(portal().accessFor("CP-1003")).isEmpty();
    }

    // ---------------------------------------------------------------- C8

    private LoanSyncService sync() {
        return new LoanSyncService(cacheRepo, creditProposals, appliedRepo, appliedService, tasks, runs, changes, CLOCK);
    }

    private static CreditProposalCacheEntry cached(String cp, String rm, String wrm) {
        CreditProposalCacheEntry e = new CreditProposalCacheEntry();
        e.setCpId(cp);
        e.setRmUserId(rm);
        e.setWrmUserId(wrm);
        return e;
    }

    @Test
    void newOwnersAreCopiedInTheirTasksMoveAndClosedLoansStopBeingMonitored() {
        CreditProposalCacheEntry moved = cached("CP-1", "rm-anil", "wrm-meera");
        CreditProposalCacheEntry closed = cached("CP-2", "rm-anil", "wrm-meera");
        CreditProposalCacheEntry same = cached("CP-3", "rm-anil", "wrm-meera");
        CreditProposalCacheEntry unknown = cached("CP-4", "rm-anil", "wrm-meera");
        when(cacheRepo.findAll()).thenReturn(List.of(moved, closed, same, unknown));
        when(runs.save(any(LoanSyncRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(creditProposals.lookup("CP-1")).thenReturn(new CreditProposalDetails("CP-1", "A", null, null, "APPROVED",
                null, new CpUser("rm-priya", null, null), new CpUser("wrm-vikram", null, null), null, null));
        when(creditProposals.lookup("CP-2")).thenReturn(new CreditProposalDetails("CP-2", "B", null, null, " repaid ",
                null, new CpUser("rm-anil", null, null), null, null, null));
        when(creditProposals.lookup("CP-3")).thenReturn(new CreditProposalDetails("CP-3", "C", null, null, "APPROVED",
                null, null, null, null, null));
        when(creditProposals.lookup("CP-4")).thenThrow(new IllegalStateException("down"));
        WorkTask rmTask = new WorkTask("CP-1", "a", WorkTask.Kind.BREACH, "t", "rm-anil", TODAY, Instant.now(CLOCK));
        WorkTask approverTask = new WorkTask("CP-1", "b", WorkTask.Kind.APPROVAL, "t", "wrm-meera", TODAY, Instant.now(CLOCK));
        when(tasks.findByCpIdAndStatus("CP-1", WorkTask.Status.OPEN)).thenReturn(List.of(rmTask, approverTask));
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-2")).thenReturn(List.of(
                applied("x1", AppliedCovenantStatus.ACTIVE), applied("x2", AppliedCovenantStatus.SUSPENDED)));

        LoanSyncService.RunView run = sync().run("support-1");

        assertThat(run.cpsChecked()).isEqualTo(4);
        assertThat(run.cpsChanged()).isEqualTo(2);
        assertThat(run.cpsClosed()).isEqualTo(1);
        assertThat(run.errors()).isEqualTo(1);
        assertThat(run.changes()).extracting(LoanSyncService.ChangeView::field).containsExactly("rm", "approver", "loanStatus");
        assertThat(moved.getRmUserId()).isEqualTo("rm-priya");
        assertThat(rmTask.getAssignee()).isEqualTo("rm-priya");
        assertThat(approverTask.getAssignee()).isEqualTo("wrm-vikram");
        verify(appliedService, times(1)).update(eq("x1"),
                eq(new UpdateAppliedCovenantRequest(null, null, AppliedCovenantStatus.SUSPENDED)));
        verify(cacheRepo, times(2)).save(any());
    }

    @Test
    void recentRunsComeBackWithTheirChanges() {
        LoanSyncRun r = new LoanSyncRun(Instant.now(CLOCK), "system");
        r.finish(Instant.now(CLOCK), 3, 1, 0, 0);
        when(runs.findTop20ByOrderByStartedAtDesc()).thenReturn(List.of(r));
        when(changes.findByRunIdIn(any())).thenReturn(new ArrayList<>(List.of(
                new LoanSyncChange(r.getId(), "CP-1", "rm", "a", "b"))));
        List<LoanSyncService.RunView> recent = sync().recent();
        assertThat(recent).singleElement().satisfies(v -> {
            assertThat(v.cpsChecked()).isEqualTo(3);
            assertThat(v.changes()).hasSize(1);
        });
        assertThat(LoanSyncService.closedStatus(null)).isNull();
        assertThat(LoanSyncService.closedStatus("Written_Off")).isEqualTo("WRITTEN_OFF");
        assertThat(LoanSyncService.closedStatus("APPROVED")).isNull();
        assertThat(Optional.ofNullable(LoanSyncService.closedStatus("closed"))).contains("CLOSED");
    }
}
