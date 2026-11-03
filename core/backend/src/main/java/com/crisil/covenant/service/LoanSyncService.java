package com.crisil.covenant.service;

import com.crisil.covenant.domain.CreditProposalDetails;
import com.crisil.covenant.dto.UpdateAppliedCovenantRequest;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.CreditProposalCacheEntry;
import com.crisil.covenant.entity.LoanSyncChange;
import com.crisil.covenant.entity.LoanSyncRun;
import com.crisil.covenant.entity.WorkTask;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import com.crisil.covenant.repository.LoanSyncChangeRepository;
import com.crisil.covenant.repository.LoanSyncRunRepository;
import com.crisil.covenant.repository.WorkTaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C8: the nightly check against the bank's loan system. For every CP Core knows, it asks the loan
 * system for the CP again and acts on what changed: a new RM or approver is copied into Core and their
 * open tasks move to them; a closed, repaid, cancelled or written-off loan has its active covenants
 * paused so nobody is chased for it. A CP the loan system cannot answer for is counted as an error and
 * left as it is. Each run and each change is recorded.
 */
@Service
public class LoanSyncService {

    private static final Logger LOG = LoggerFactory.getLogger(LoanSyncService.class);
    static final Set<String> CLOSED = Set.of("CLOSED", "REPAID", "CANCELLED", "WRITTEN_OFF");

    public record ChangeView(String cpId, String field, String oldValue, String newValue) {}

    public record RunView(String id, Instant startedAt, Instant finishedAt, int cpsChecked, int cpsChanged,
                          int cpsClosed, int errors, String triggeredBy, List<ChangeView> changes) {}

    private final CreditProposalCacheRepository cacheRepo;
    private final CreditProposalClient client;
    private final AppliedCovenantRepository appliedRepo;
    private final AppliedCovenantService appliedService;
    private final WorkTaskRepository tasks;
    private final LoanSyncRunRepository runs;
    private final LoanSyncChangeRepository changes;
    private final Clock clock;

    public LoanSyncService(CreditProposalCacheRepository cacheRepo, CreditProposalClient client,
                           AppliedCovenantRepository appliedRepo, AppliedCovenantService appliedService,
                           WorkTaskRepository tasks, LoanSyncRunRepository runs, LoanSyncChangeRepository changes,
                           Clock clock) {
        this.cacheRepo = cacheRepo;
        this.client = client;
        this.appliedRepo = appliedRepo;
        this.appliedService = appliedService;
        this.tasks = tasks;
        this.runs = runs;
        this.changes = changes;
        this.clock = clock;
    }

    @Transactional
    public RunView run(String actor) {
        LoanSyncRun run = runs.save(new LoanSyncRun(Instant.now(clock), actor == null ? "system" : actor));
        int checked = 0;
        int changed = 0;
        int closed = 0;
        int errors = 0;
        List<LoanSyncChange> found = new ArrayList<>();
        for (CreditProposalCacheEntry cp : cacheRepo.findAll()) {
            checked++;
            CreditProposalDetails d;
            try {
                d = client.lookup(cp.getCpId());
            } catch (RuntimeException ex) {
                d = null;
            }
            if (d == null) {
                errors++;
                continue;
            }
            List<LoanSyncChange> mine = new ArrayList<>();
            String rm = d.rm() == null ? null : d.rm().userId();
            String wrm = d.wrm() == null ? null : d.wrm().userId();
            if (rm != null && !rm.equals(cp.getRmUserId())) {
                mine.add(new LoanSyncChange(run.getId(), cp.getCpId(), "rm", cp.getRmUserId(), rm));
                moveTasks(cp.getCpId(), cp.getRmUserId(), rm);
                cp.setRmUserId(rm);
            }
            if (wrm != null && !wrm.equals(cp.getWrmUserId())) {
                mine.add(new LoanSyncChange(run.getId(), cp.getCpId(), "approver", cp.getWrmUserId(), wrm));
                moveTasks(cp.getCpId(), cp.getWrmUserId(), wrm);
                cp.setWrmUserId(wrm);
            }
            String status = closedStatus(d.approvalStatus());
            if (status != null) {
                int paused = pauseCovenants(cp.getCpId());
                if (paused > 0) {
                    mine.add(new LoanSyncChange(run.getId(), cp.getCpId(), "loanStatus", "ACTIVE",
                            status + " (" + paused + " covenant(s) paused)"));
                    closed++;
                }
            }
            if (!mine.isEmpty()) {
                changed++;
                cacheRepo.save(cp);
                found.addAll(mine);
            }
        }
        changes.saveAll(found);
        run.finish(Instant.now(clock), checked, changed, closed, errors);
        runs.save(run);
        LOG.info("Loan sync: checked={}, changed={}, closed={}, errors={}", checked, changed, closed, errors);
        return view(run, found);
    }

    /** The closed status the loan system reports (any letter case), or null while the loan is live. */
    static String closedStatus(String reported) {
        if (reported == null) {
            return null;
        }
        String trimmed = reported.trim();
        return CLOSED.stream().filter(c -> ScheduledReportService.sameIgnoringCase(c, trimmed)).findFirst().orElse(null);
    }

    private void moveTasks(String cpId, String from, String to) {
        for (WorkTask t : tasks.findByCpIdAndStatus(cpId, WorkTask.Status.OPEN)) {
            if (Objects.equals(t.getAssignee(), from)) {
                t.setAssignee(to);
                tasks.save(t);
            }
        }
    }

    private int pauseCovenants(String cpId) {
        int paused = 0;
        for (AppliedCovenant a : appliedRepo.findByCpIdOrderByIdAsc(cpId)) {
            if (a.getStatus() == AppliedCovenantStatus.ACTIVE) {
                appliedService.update(a.getId(), new UpdateAppliedCovenantRequest(null, null, AppliedCovenantStatus.SUSPENDED));
                paused++;
            }
        }
        return paused;
    }

    @Transactional(readOnly = true)
    public List<RunView> recent() {
        List<LoanSyncRun> latest = runs.findTop20ByOrderByStartedAtDesc();
        Map<String, List<LoanSyncChange>> byRun = changes.findByRunIdIn(latest.stream().map(LoanSyncRun::getId).toList())
                .stream().collect(Collectors.groupingBy(LoanSyncChange::getRunId));
        return latest.stream().map(r -> view(r, byRun.getOrDefault(r.getId(), List.of()))).toList();
    }

    private static RunView view(LoanSyncRun r, List<LoanSyncChange> found) {
        return new RunView(r.getId(), r.getStartedAt(), r.getFinishedAt(), r.getCpsChecked(), r.getCpsChanged(),
                r.getCpsClosed(), r.getErrors(), r.getTriggeredBy(),
                found.stream().map(c -> new ChangeView(c.getCpId(), c.getField(), c.getOldValue(), c.getNewValue()))
                        .toList());
    }
}
