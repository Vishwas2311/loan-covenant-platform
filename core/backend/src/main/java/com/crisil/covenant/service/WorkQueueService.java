package com.crisil.covenant.service;

import com.crisil.covenant.dto.CertificateRequirementView;
import com.crisil.covenant.dto.CpReviewRow;
import com.crisil.covenant.dto.UpdateWorkTaskRequest;
import com.crisil.covenant.dto.WorkTaskView;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.CreditProposalCacheEntry;
import com.crisil.covenant.entity.WorkTask;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.DispositionApproval;
import com.crisil.covenant.enums.TimingStatus;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import com.crisil.covenant.repository.WorkTaskRepository;
import com.crisil.covenant.util.IdBatches;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * C4: the work queue. Before each read the open tasks of the user's CPs that changed (see
 * {@link TaskSyncTracker}) are brought in line with the covenants: a task is opened for each thing that needs a person (a request to decide, a breach with
 * no decision, an overdue check, a person-checked result to record, an overdue compliance
 * certificate) and closed as RESOLVED once that has cleared. Requests go to the CP's approver; the
 * rest to its RM. A task still open after its due date is escalated: the approver sees it too.
 *
 * <p>A read syncs inline only for portfolios up to {@value #INLINE_SYNC_LIMIT} CPs (an RM's or approver's).
 * Bank-wide roles see thousands of CPs; for them, and for CPs nobody opens, {@link TaskSyncJob} keeps the
 * tasks current in the background, one transaction per CP.
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
    /** Largest portfolio whose tasks a read syncs before answering. */
    static final int INLINE_SYNC_LIMIT = 300;
    /** The background sync re-checks an unchanged CP this often; changed CPs are picked up on every run. */
    static final Duration BACKGROUND_STALE_AFTER = Duration.ofMinutes(30);

    private static final Logger LOG = LoggerFactory.getLogger(WorkQueueService.class);

    private final TaskSyncTracker tracker;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

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
        sync(cpId, rm, approver, null);
    }

    /** As {@link #sync(String, String, String)}, with the CP's review rows already loaded (or null to load them). */
    void sync(String cpId, String rm, String approver, List<CpReviewRow> rows) {
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock);
        Map<String, Needed> wanted = new HashMap<>();
        for (Needed n : needed(rows != null ? rows : reviewService.reviewRows(cpId), certificates.forCp(cpId))) {
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
        Set<String> cps = new HashSet<>(monitoringService.visibleCpIds(user));
        Map<String, CreditProposalCacheEntry> owners = new HashMap<>();
        IdBatches.fetch(cps, cacheRepo::findAllById).forEach(e -> owners.put(e.getCpId(), e));
        Set<String> changed = tracker.takeChangedAppliedCovenants();
        if (!changed.isEmpty()) {
            IdBatches.fetch(changed, appliedRepo::findAllById).stream().map(AppliedCovenant::getCpId).forEach(tracker::markChanged);
        }
        Instant now = Instant.now(clock);
        if (cps.size() <= INLINE_SYNC_LIMIT) {
            List<String> due = cps.stream().filter(cp -> tracker.needsSync(cp, now)).toList();
            // One batched load of the review rows of every CP due, instead of about eight queries per CP.
            Map<String, List<CpReviewRow>> rows = due.isEmpty() ? Map.of() : reviewService.reviewRowsFor(due);
            for (String cp : due) {
                if (syncIfNeeded(cp, owners.get(cp), now, tracker.resyncAfter(), rows.get(cp))) {
                    releaseSyncedEntities();
                }
            }
            repo.flush();
        }
        List<WorkTaskView> out = new ArrayList<>();
        for (WorkTask t : tasksOn(cps)) {
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

    /**
     * Background sync of every CP (see {@link TaskSyncJob}): those that changed or were never synced, and
     * the rest once {@link #BACKGROUND_STALE_AFTER} has passed. Each CP gets its own transaction, so one
     * failure skips only that CP. Returns how many CPs were synced.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int syncAll() {
        List<String> all = cacheRepo.findAllCpIds();
        Map<String, CreditProposalCacheEntry> owners = new HashMap<>();
        IdBatches.fetch(all, cacheRepo::findAllById).forEach(e -> owners.put(e.getCpId(), e));
        Set<String> changed = tracker.takeChangedAppliedCovenants();
        if (!changed.isEmpty()) {
            IdBatches.fetch(changed, appliedRepo::findAllById).stream().map(AppliedCovenant::getCpId)
                    .forEach(tracker::markChanged);
        }
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Instant now = Instant.now(clock);
        int synced = 0;
        for (String cp : all) {
            if (!tracker.needsSync(cp, now, BACKGROUND_STALE_AFTER)) {
                continue;
            }
            try {
                Boolean done = tx.execute(s -> syncIfNeeded(cp, owners.get(cp), now, BACKGROUND_STALE_AFTER, null));
                synced += Boolean.TRUE.equals(done) ? 1 : 0;
            } catch (RuntimeException ex) {
                LOG.warn("Task sync failed for CP {}: {}", cp.replace('\n', '_').replace('\r', '_'),
                        ex.getClass().getSimpleName());
            }
        }
        return synced;
    }

    /** Syncs one CP's tasks unless another thread on this node just did. Returns whether it synced. */
    private boolean syncIfNeeded(String cp, CreditProposalCacheEntry owner, Instant now, Duration staleAfter,
                                 List<CpReviewRow> rows) {
        synchronized (tracker.lock(cp)) {
            if (!tracker.needsSync(cp, now, staleAfter)) {
                return false;
            }
            tracker.startSync(cp);
            sync(cp, owner == null ? null : owner.getRmUserId(), owner == null ? null : owner.getWrmUserId(), rows);
            tracker.synced(cp, now);
            return true;
        }
    }

    /**
     * Writes one CP's task changes and empties the persistence context. Without this every query of a long
     * sync re-checks every entity loaded for the CPs before it, and a sync of N CPs costs N squared.
     */
    private void releaseSyncedEntities() {
        if (entityManager != null) {
            entityManager.flush();
            entityManager.clear();
        }
    }

    /** Tasks on {@code cps} by due date, in batches below SQL Server's 2,100-parameter limit. */
    private List<WorkTask> tasksOn(Collection<String> cps) {
        List<WorkTask> tasks = IdBatches.fetch(cps, repo::findByCpIdInOrderByDueDateAsc);
        tasks.sort(Comparator.comparing(WorkTask::getDueDate, Comparator.nullsLast(Comparator.naturalOrder())));
        return tasks;
    }

    /** Reassign, re-date, add a note to, or finish a task on one of the user's CPs. */
    public WorkTaskView update(String id, UpdateWorkTaskRequest req, String user) {
        WorkTask t = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Task " + id + " not found"));
        if (!monitoringService.visibleCpIds(user).contains(t.getCpId())) {
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
