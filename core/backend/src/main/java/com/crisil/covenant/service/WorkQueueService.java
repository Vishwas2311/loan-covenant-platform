package com.crisil.covenant.service;

import com.crisil.covenant.domain.CpMonitoringSummary;
import com.crisil.covenant.dto.CertificateRequirementView;
import com.crisil.covenant.dto.CpReviewRow;
import com.crisil.covenant.dto.UpdateWorkTaskRequest;
import com.crisil.covenant.dto.WorkTaskView;
import com.crisil.covenant.entity.CreditProposalCacheEntry;
import com.crisil.covenant.entity.WorkTask;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.DispositionApproval;
import com.crisil.covenant.enums.TimingStatus;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import com.crisil.covenant.repository.WorkTaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * C4: the work queue. Before each read the open tasks of the user's CPs that changed (see
 * {@link TaskSyncTracker}) are brought in line with the covenants: a task is opened for each thing that needs a person (a request to decide, a breach with
 * no decision, an overdue check, a person-checked result to record, an overdue compliance
 * certificate) and closed as RESOLVED once that has cleared. Requests go to the CP's approver; the
 * rest to its RM. A task still open after its due date is escalated: the approver sees it too.
 */
@Service
@Transactional
public class WorkQueueService {

    /** Days given to do each kind of task when it is opened. */
    static final Map<WorkTask.Kind, Integer> DAYS = Map.of(
            WorkTask.Kind.APPROVAL, 2, WorkTask.Kind.BREACH, 7, WorkTask.Kind.OVERDUE, 3,
            WorkTask.Kind.REVIEW, 5, WorkTask.Kind.CERTIFICATE, 3);

    private final MonitoringService monitoringService;
    private final ReviewService reviewService;
    private final CertificateRequirementService certificates;
    private final CreditProposalCacheRepository cacheRepo;
    private final WorkTaskRepository repo;
    private final AppliedCovenantRepository appliedRepo;
    private final TaskSyncTracker tracker;
    private final Clock clock;

    public WorkQueueService(MonitoringService monitoringService, ReviewService reviewService,
                            CertificateRequirementService certificates, CreditProposalCacheRepository cacheRepo,
                            WorkTaskRepository repo, AppliedCovenantRepository appliedRepo, TaskSyncTracker tracker,
                            Clock clock) {
        this.monitoringService = monitoringService;
        this.reviewService = reviewService;
        this.certificates = certificates;
        this.cacheRepo = cacheRepo;
        this.repo = repo;
        this.appliedRepo = appliedRepo;
        this.tracker = tracker;
        this.clock = clock;
    }

    /** What a task should exist for, keyed by kind + covenant (or certificate requirement). */
    record Needed(WorkTask.Kind kind, String refId, String title, boolean forApprover) {
        String key() {
            return kind + "|" + refId;
        }
    }

    static List<Needed> needed(List<CpReviewRow> rows, List<CertificateRequirementView> requirements) {
        List<Needed> out = new ArrayList<>();
        for (CpReviewRow r : rows) {
            if (r.approvalStatus() == DispositionApproval.REQUESTED) {
                out.add(new Needed(WorkTask.Kind.APPROVAL, r.appliedCovenantId(),
                        "Decide the request by " + r.requestedBy() + " on " + r.covenantName(), true));
            } else if (r.dispositionable()) {
                out.add(new Needed(WorkTask.Kind.BREACH, r.appliedCovenantId(),
                        r.covenantName() + " is breached: decide a waiver or extension, or follow up", false));
            }
            if (r.timingStatus() == TimingStatus.OVERDUE) {
                out.add(new Needed(WorkTask.Kind.OVERDUE, r.appliedCovenantId(),
                        r.covenantName() + " check is overdue: get the figures in", false));
            }
            if (r.latestResult() == AssessmentResult.PENDING_REVIEW) {
                out.add(new Needed(WorkTask.Kind.REVIEW, r.appliedCovenantId(),
                        "Record the result of " + r.covenantName(), false));
            }
        }
        for (CertificateRequirementView q : requirements) {
            if (q.state() == CertificateRequirementView.State.OVERDUE) {
                out.add(new Needed(WorkTask.Kind.CERTIFICATE, q.id(),
                        "Chase the compliance certificate for period " + q.periodEnd(), false));
            }
        }
        return out;
    }

    /** Bring one CP's open tasks in line with what it needs now. */
    void sync(String cpId, String rm, String approver) {
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock);
        Map<String, Needed> wanted = new HashMap<>();
        for (Needed n : needed(reviewService.reviewRows(cpId), certificates.forCp(cpId))) {
            wanted.putIfAbsent(n.key(), n);
        }
        for (WorkTask open : repo.findByCpIdAndStatus(cpId, WorkTask.Status.OPEN)) {
            Needed still = wanted.remove(open.getKind() + "|" + open.getAppliedCovenantId());
            if (still == null) {
                open.close(WorkTask.Status.RESOLVED, "system", now);
            } else {
                open.setTitle(still.title());
            }
        }
        for (Needed n : wanted.values()) {
            String who = n.forApprover() && approver != null ? approver : rm != null ? rm : approver;
            if (who != null) {
                repo.save(new WorkTask(cpId, n.refId(), n.kind(), n.title(), who,
                        today.plusDays(DAYS.get(n.kind())), now));
            }
        }
    }

    /**
     * Tasks on the user's CPs, after syncing the ones that changed. {@code mine} keeps the user's own tasks plus the
     * escalated ones on CPs where they are the approver; {@code open} keeps only open tasks.
     */
    public List<WorkTaskView> tasksFor(String user, boolean mine, boolean open) {
        LocalDate today = LocalDate.now(clock);
        Set<String> cps = monitoringService.summary(user).stream().map(CpMonitoringSummary::cpId)
                .collect(Collectors.toSet());
        Map<String, CreditProposalCacheEntry> owners = new HashMap<>();
        cacheRepo.findAllById(cps).forEach(e -> owners.put(e.getCpId(), e));
        Set<String> changed = tracker.takeChangedAppliedCovenants();
        if (!changed.isEmpty()) {
            appliedRepo.findAllById(changed).stream().map(AppliedCovenant::getCpId).forEach(tracker::markChanged);
        }
        Instant now = Instant.now(clock);
        for (String cp : cps) {
            if (!tracker.needsSync(cp, now)) {
                continue;
            }
            synchronized (tracker.lock(cp)) {
                if (tracker.needsSync(cp, now)) {
                    tracker.startSync(cp);
                    CreditProposalCacheEntry e = owners.get(cp);
                    sync(cp, e == null ? null : e.getRmUserId(), e == null ? null : e.getWrmUserId());
                    tracker.synced(cp, now);
                }
            }
        }
        repo.flush();
        List<WorkTaskView> out = new ArrayList<>();
        for (WorkTask t : repo.findByCpIdInOrderByDueDateAsc(cps)) {
            WorkTaskView v = WorkTaskView.from(t, today);
            CreditProposalCacheEntry e = owners.get(t.getCpId());
            boolean approverOfCp = e != null && Objects.equals(user, e.getWrmUserId());
            if (mine && !Objects.equals(user, t.getAssignee()) && !(v.escalated() && approverOfCp)) {
                continue;
            }
            if (open && t.getStatus() != WorkTask.Status.OPEN) {
                continue;
            }
            out.add(v);
        }
        return out;
    }

    /** Reassign, re-date, add a note to, or finish a task on one of the user's CPs. */
    public WorkTaskView update(String id, UpdateWorkTaskRequest req, String user) {
        WorkTask t = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Task " + id + " not found"));
        Set<String> mine = monitoringService.summary(user).stream().map(CpMonitoringSummary::cpId)
                .collect(Collectors.toSet());
        if (!mine.contains(t.getCpId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task " + id + " not found");
        }
        if (t.getStatus() != WorkTask.Status.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This task is already closed");
        }
        if (req.assignee() != null && !req.assignee().isBlank()) {
            t.setAssignee(req.assignee().trim());
        }
        if (req.dueDate() != null) {
            t.setDueDate(req.dueDate());
        }
        if (req.note() != null) {
            t.setNote(req.note().isBlank() ? null : req.note().trim());
        }
        if (Boolean.TRUE.equals(req.done())) {
            t.close(WorkTask.Status.DONE, user, Instant.now(clock));
        }
        return WorkTaskView.from(repo.save(t), LocalDate.now(clock));
    }
}
