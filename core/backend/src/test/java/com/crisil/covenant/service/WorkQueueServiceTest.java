package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.domain.CpMonitoringSummary;
import com.crisil.covenant.dto.CertificateRequirementView;
import com.crisil.covenant.dto.CpReviewRow;
import com.crisil.covenant.dto.UpdateWorkTaskRequest;
import com.crisil.covenant.dto.WorkTaskView;
import com.crisil.covenant.entity.CreditProposalCacheEntry;
import com.crisil.covenant.entity.WorkTask;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.enums.CovenantStatusSource;
import com.crisil.covenant.enums.DispositionApproval;
import com.crisil.covenant.enums.TimingStatus;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import com.crisil.covenant.repository.WorkTaskRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/** C4: tasks are opened from the covenant state, closed when it clears, assigned and escalated. */
@ExtendWith(MockitoExtension.class)
class WorkQueueServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T09:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    @Mock private MonitoringService monitoringService;
    @Mock private ReviewService reviewService;
    @Mock private CertificateRequirementService certificates;
    @Mock private CreditProposalCacheRepository cacheRepo;
    @Mock private WorkTaskRepository repo;

    private final List<WorkTask> stored = new ArrayList<>();
    private WorkQueueService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new WorkQueueService(monitoringService, reviewService, certificates, cacheRepo, repo, CLOCK);
        lenient().when(monitoringService.summary("rm-anil")).thenReturn(List.of(new CpMonitoringSummary("CP-1", 3, 1, 1, 0, 1, 0)));
        lenient().when(monitoringService.summary("wrm-meera")).thenReturn(List.of(new CpMonitoringSummary("CP-1", 3, 1, 1, 0, 1, 0)));
        lenient().when(monitoringService.summary("rm-priya")).thenReturn(List.of());
        lenient().when(cacheRepo.findAllById(any())).thenReturn(List.of(owner("CP-1", "rm-anil", "wrm-meera")));
        lenient().when(repo.save(any(WorkTask.class))).thenAnswer(inv -> {
            WorkTask t = inv.getArgument(0);
            if (!stored.contains(t)) {
                stored.add(t);
            }
            return t;
        });
        lenient().when(repo.findByCpIdAndStatus("CP-1", WorkTask.Status.OPEN)).thenAnswer(inv ->
                stored.stream().filter(t -> t.getStatus() == WorkTask.Status.OPEN).toList());
        lenient().when(repo.findByCpIdInOrderByDueDateAsc(anyCollection())).thenAnswer(inv -> List.copyOf(stored));
        lenient().when(certificates.forCp("CP-1")).thenReturn(List.of());
    }

    private static CpReviewRow row(String id, AssessmentResult result, boolean dispositionable,
                                   DispositionApproval approval, TimingStatus timing) {
        return new CpReviewRow(id, "m", "Cov " + id, "DSCR >= 1.5", null, AssessmentType.AUTO, null, null, null,
                result, "a" + id, null, CovenantDispositionStatus.BREACHED, null, CovenantStatusSource.SYSTEM, null,
                dispositionable, null, null, timing, 0, List.of(), approval,
                approval == null ? null : CovenantDispositionStatus.WAIVER, approval == null ? null : "rm-anil", false);
    }

    @Test
    void opensTasksForWhatNeedsAPersonAndGivesRequestsToTheApprover() {
        when(reviewService.reviewRows("CP-1")).thenReturn(List.of(
                row("1", AssessmentResult.BREACHED, true, null, TimingStatus.OVERDUE),
                row("2", AssessmentResult.BREACHED, true, DispositionApproval.REQUESTED, null),
                row("3", AssessmentResult.PENDING_REVIEW, false, null, null),
                row("4", AssessmentResult.COMPLIANT, false, null, TimingStatus.UP_TO_DATE)));
        when(certificates.forCp("CP-1")).thenReturn(List.of(new CertificateRequirementView("q1", "CP-1",
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 31), null,
                CertificateRequirementView.State.OVERDUE, 4, List.of())));

        List<WorkTaskView> all = service.tasksFor("rm-anil", false, true);

        assertThat(all).extracting(WorkTaskView::kind)
                .containsExactlyInAnyOrder("BREACH", "OVERDUE", "APPROVAL", "REVIEW", "CERTIFICATE");
        WorkTaskView approval = all.stream().filter(t -> t.kind().equals("APPROVAL")).findFirst().orElseThrow();
        assertThat(approval.assignee()).isEqualTo("wrm-meera");
        assertThat(approval.dueDate()).isEqualTo(TODAY.plusDays(2));
        assertThat(approval.title()).isEqualTo("Decide the request by rm-anil on Cov 2");
        assertThat(service.tasksFor("rm-anil", true, true)).extracting(WorkTaskView::kind)
                .containsExactlyInAnyOrder("BREACH", "OVERDUE", "REVIEW", "CERTIFICATE");
    }

    @Test
    void aClearedProblemResolvesItsTaskAndNothingIsOpenedTwice() {
        when(reviewService.reviewRows("CP-1")).thenReturn(List.of(row("1", AssessmentResult.BREACHED, true, null, null)));
        service.tasksFor("rm-anil", true, true);
        service.tasksFor("rm-anil", true, true);
        assertThat(stored).hasSize(1);

        when(reviewService.reviewRows("CP-1")).thenReturn(List.of(row("1", AssessmentResult.COMPLIANT, false, null, null)));
        assertThat(service.tasksFor("rm-anil", true, true)).isEmpty();
        assertThat(stored.get(0).getStatus()).isEqualTo(WorkTask.Status.RESOLVED);
        assertThat(stored.get(0).getClosedBy()).isEqualTo("system");
        assertThat(service.tasksFor("rm-anil", true, false)).hasSize(1);
    }

    @Test
    void anOverdueTaskIsEscalatedToTheApprover() throws Exception {
        WorkTask late = new WorkTask("CP-1", "1", WorkTask.Kind.BREACH, "late", "rm-anil", TODAY.minusDays(1),
                Instant.parse("2026-10-20T00:00:00Z"));
        stored.add(late);
        when(reviewService.reviewRows("CP-1")).thenReturn(List.of(row("1", AssessmentResult.BREACHED, true, null, null)));

        List<WorkTaskView> forApprover = service.tasksFor("wrm-meera", true, true);

        assertThat(forApprover).singleElement().satisfies(t -> {
            assertThat(t.escalated()).isTrue();
            assertThat(t.assignee()).isEqualTo("rm-anil");
        });
    }

    @Test
    void updateReassignsRedatesNotesAndFinishes() {
        WorkTask t = new WorkTask("CP-1", "1", WorkTask.Kind.BREACH, "x", "rm-anil", TODAY, Instant.now(CLOCK));
        when(repo.findById(t.getId())).thenReturn(Optional.of(t));

        WorkTaskView v = service.update(t.getId(),
                new UpdateWorkTaskRequest(" wrm-meera ", TODAY.plusDays(5), " called the CFO ", null), "rm-anil");
        assertThat(v.assignee()).isEqualTo("wrm-meera");
        assertThat(v.dueDate()).isEqualTo(TODAY.plusDays(5));
        assertThat(v.note()).isEqualTo("called the CFO");

        v = service.update(t.getId(), new UpdateWorkTaskRequest(null, null, " ", true), "rm-anil");
        assertThat(v.status()).isEqualTo("DONE");
        assertThat(v.note()).isNull();
        assertThat(v.closedBy()).isEqualTo("rm-anil");

        assertThatThrownBy(() -> service.update(t.getId(), new UpdateWorkTaskRequest(null, null, null, true), "rm-anil"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("already closed");
    }

    @Test
    void someoneWithoutTheCpCannotSeeOrChangeItsTask() {
        WorkTask t = new WorkTask("CP-1", "1", WorkTask.Kind.BREACH, "x", "rm-anil", TODAY, Instant.now(CLOCK));
        when(repo.findById(t.getId())).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> service.update(t.getId(), new UpdateWorkTaskRequest(null, null, null, true), "rm-priya"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("not found");
        when(repo.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update("nope", new UpdateWorkTaskRequest(null, null, null, true), "rm-anil"))
                .isInstanceOf(ResponseStatusException.class);
        verify(repo, never()).save(any());
    }

    private static CreditProposalCacheEntry owner(String cp, String rm, String wrm) throws Exception {
        CreditProposalCacheEntry e = new CreditProposalCacheEntry();
        set(e, "cpId", cp);
        set(e, "rmUserId", rm);
        set(e, "wrmUserId", wrm);
        return e;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
