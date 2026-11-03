package com.crisil.covenant.service;

import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.repository.AssessmentRepository;
import com.crisil.covenant.repository.AssessmentRepository.AppliedCovenantLastExecuted;
import com.crisil.covenant.service.AssessmentService;
import com.crisil.covenant.enums.AssessmentTrigger;
import com.crisil.covenant.config.AuditingConfig;
import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.domain.CpUser;
import com.crisil.covenant.service.CreditProposalClient;
import com.crisil.covenant.domain.CreditProposalDetails;
import com.crisil.covenant.domain.CpMonitoringSummary;
import com.crisil.covenant.service.MonitoringService;
import com.crisil.covenant.domain.MonitoringTiming;
import com.crisil.covenant.util.MonitoringTimingCalculator;
import com.crisil.covenant.enums.TimingStatus;
import com.crisil.covenant.dto.NotificationRequest;
import com.crisil.covenant.dto.NotificationResult;
import com.crisil.covenant.dto.RunResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import com.crisil.covenant.entity.NotificationAudit;
import com.crisil.covenant.repository.NotificationAuditRepository;

/**
 * US-MON-14: the daily (and on-demand) breach-assessment run. For a given assessment date it
 * <ol>
 *   <li>finds every ACTIVE covenant <b>due</b> on that date (incl. overdue) via the same
 *       {@link MonitoringTimingCalculator} the CP Monitoring screen uses;</li>
 *   <li>assesses each with the shared {@link AssessmentService#runAssessment} primitive (the same call
 *       the per-covenant Quartz job makes). This is naturally idempotent: {@code compute} advances a
 *       covenant's next due date to the first cron occurrence <em>strictly after</em> its last
 *       assessment, so a covenant already assessed on the run date (by an earlier run, or the
 *       per-covenant job) is no longer "due" and is skipped — a same-day re-run does not
 *       double-assess or double-email;</li>
 *   <li>builds each touched CP's monitoring summary (identical to the screen) and its RM/WRM;</li>
 *   <li>merges CPs by <b>(RM, WRM) pair</b> — RM {@code emailTo}, WRM {@code emailCC} — and sends one
 *       notification per pair via the resilient {@link NotificationClient};</li>
 *   <li>appends one {@link NotificationAudit} row per call (plus one for any touched CP group with no
 *       recipient), all sharing the run's {@code runId}.</li>
 * </ol>
 *
 * <p>Deliberately <b>not</b> {@code @Transactional} at the class level: like {@code AssessmentJob}, it
 * orchestrates many independent units of work. Each {@code runAssessment} and each audit
 * {@code save} runs in its own transaction, so one covenant's failure (or a poisoned transaction)
 * neither aborts the batch nor rolls back the assessments/audit rows already committed.
 */
@Service
public class BreachNotificationService {

    private static final Logger LOG = LoggerFactory.getLogger(BreachNotificationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Column widths of {@code cov_notification_audit}; bodies are truncated so an oversized
     *  payload can never fail the append-only insert. */
    private static final int BODY_MAX = 4000;
    private static final int ERROR_MAX = 512;

    /** The daily sweep reads ACTIVE covenants a page at a time so a large book never loads at once. */
    private static final int ACTIVE_PAGE_SIZE = 500;

    private final AppliedCovenantRepository appliedRepo;
    private final AssessmentRepository assessmentRepo;
    private final AssessmentService assessmentService;
    private final MonitoringService monitoringService;
    private final CreditProposalClient creditProposalClient;
    private final NotificationClient notificationClient;
    private final NotificationAuditRepository auditRepo;

    public BreachNotificationService(AppliedCovenantRepository appliedRepo,
                                     AssessmentRepository assessmentRepo,
                                     AssessmentService assessmentService,
                                     MonitoringService monitoringService,
                                     CreditProposalClient creditProposalClient,
                                     NotificationClient notificationClient,
                                     NotificationAuditRepository auditRepo) {
        this.appliedRepo = appliedRepo;
        this.assessmentRepo = assessmentRepo;
        this.assessmentService = assessmentService;
        this.monitoringService = monitoringService;
        this.creditProposalClient = creditProposalClient;
        this.notificationClient = notificationClient;
        this.auditRepo = auditRepo;
    }

    /**
     * Run the sweep for {@code asOf} (defaults to today in the system zone), attributing the
     * assessments and audit rows to {@code triggeredBy} (falling back to the request user / system).
     */
    public RunResult run(LocalDate asOf, String triggeredBy) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate runDate = asOf != null ? asOf : LocalDate.now(zone);
        String runId = UUID.randomUUID().toString();
        String actor = resolveActor(triggeredBy);
        // US: one workflow case per CP across the whole sweep — share a single run context so a CP
        // with several due covenants opens exactly one case (all its rows share the caseId).
        WorkflowCaseContext ctx = assessmentService.newRunContext();

        // 1 + 2. Assess every ACTIVE covenant due on runDate; remember which CPs we touched. The book
        // is streamed a page at a time (bounded memory), and each page resolves its covenants' last
        // executed dates in one grouped query instead of an N+1 per-covenant lookup.
        Set<String> touchedCps = new LinkedHashSet<>();
        int covenantsAssessed = 0;
        int pageNumber = 0;
        Page<AppliedCovenant> page;
        do {
            page = appliedRepo.findByStatus(AppliedCovenantStatus.ACTIVE,
                    PageRequest.of(pageNumber, ACTIVE_PAGE_SIZE, Sort.by("id")));
            Map<String, Instant> lastExecutedByCovenant = latestExecutedAtFor(page.getContent());
            for (AppliedCovenant ac : page.getContent()) {
                MonitoringTiming timing =
                        timingFor(ac, lastExecutedByCovenant.get(ac.getId()), runDate, zone);
                if (!isDue(timing, runDate)) {
                    continue;
                }
                try {
                    assessmentService.runAssessment(ac.getId(), AssessmentTrigger.SCHEDULED, actor, ctx);
                    covenantsAssessed++;
                    touchedCps.add(ac.getCpId());
                } catch (RuntimeException ex) {
                    // Batch isolation: log and keep going so one covenant can't abort the run.
                    // Parameterized with an inline CR/LF-sanitised detail so no tainted value reaches the sink.
                    String safe = ("run " + runId + ": " + ex.getMessage())
                            .replace('\n', '_').replace('\r', '_');
                    String safeId = ac.getId() == null ? "null"
                            : ac.getId().replace('\n', '_').replace('\r', '_');
                    LOG.warn("Assessment failed for applied covenant {}: {}", safeId, safe);
                }
            }
            pageNumber++;
        } while (page.hasNext());

        // 3 + 4. Per touched CP: monitoring summary + RM/WRM, grouped by (RM email, WRM email).
        Map<RecipientKey, PairGroup> groups = new LinkedHashMap<>();
        List<String> noRecipientCps = new ArrayList<>();
        for (String cpId : touchedCps) {
            CpMonitoringSummary summary = monitoringService.summaryFor(cpId);
            CpUser rm = null;
            CpUser wrm = null;
            try {
                CreditProposalDetails details = creditProposalClient.lookup(cpId);
                if (details != null) {
                    rm = details.rm();
                    wrm = details.wrm();
                }
            } catch (RuntimeException ex) {
                String safe = ("CP " + cpId + " in run " + runId + ": " + ex.getMessage())
                        .replace('\n', '_').replace('\r', '_');
                LOG.warn("CP lookup failed for {}", safe);
            }
            String rmEmail = email(rm);
            if (rmEmail == null) {
                noRecipientCps.add(cpId);
                continue;
            }
            String wrmEmail = email(wrm);
            groups.computeIfAbsent(new RecipientKey(rmEmail, wrmEmail),
                            k -> new PairGroup(rmEmail, wrmEmail))
                    .add(cpId, summary);
        }

        // 5. Send one notification per (RM, WRM) pair and audit every call.
        int sent = 0;
        int failed = 0;
        for (PairGroup group : groups.values()) {
            if (sendAndAudit(runId, actor, group)) {
                sent++;
            } else {
                failed++;
            }
        }
        // A touched CP with no RM is assessed but not notified — recorded as one FAILED audit row.
        if (!noRecipientCps.isEmpty()) {
            failed++;
            auditNoRecipient(runId, actor, noRecipientCps);
        }

        // Parameterized with an inline CR/LF-sanitised detail (values are machine-generated, but the
        // sanitiser keeps FindSecBugs' taint analysis satisfied at the logger sink).
        String summaryLine = ("run " + runId + " for " + runDate + ": assessed=" + covenantsAssessed
                + ", cpsTouched=" + touchedCps.size() + ", sent=" + sent + ", failed=" + failed)
                .replace('\n', '_').replace('\r', '_');
        LOG.info("Breach {}", summaryLine);
        return new RunResult(runId, covenantsAssessed, touchedCps.size(), sent, failed);
    }

    /** Build, send and audit the notification for one (RM, WRM) pair. Returns success. */
    private boolean sendAndAudit(String runId, String actor, PairGroup group) {
        NotificationRequest request = new NotificationRequest(
                "COV", "Cov_Template", group.rmEmail(), group.wrmEmail(),
                new NotificationRequest.Variables(group.rows()), "HIGH");

        long start = System.currentTimeMillis();
        NotificationResult result = notificationClient.send(request);
        long durationMs = System.currentTimeMillis() - start;
        boolean success = result != null && result.success();

        auditRepo.save(new NotificationAudit(
                runId,
                group.rmEmail(),
                group.wrmEmail(),
                truncate(String.join(",", group.cpIds()), 2000),
                truncate(serialise(request), BODY_MAX),
                truncate(result == null ? null : result.responseBody(), BODY_MAX),
                result == null ? null : result.httpStatus(),
                success ? NotificationAudit.Outcome.SUCCESS : NotificationAudit.Outcome.FAILED,
                success ? null : truncate(errorOf(result), ERROR_MAX),
                durationMs,
                actor,
                Instant.now()));
        return success;
    }

    /** One aggregate FAILED audit row for every touched CP group that had no RM recipient. */
    private void auditNoRecipient(String runId, String actor, List<String> cpIds) {
        auditRepo.save(new NotificationAudit(
                runId, null, null, truncate(String.join(",", cpIds), 2000),
                null, null, null, NotificationAudit.Outcome.FAILED,
                "No recipient: CP has no RM email", null, actor, Instant.now()));
    }

    private static String resolveActor(String triggeredBy) {
        if (triggeredBy != null && !triggeredBy.isBlank()) {
            return triggeredBy;
        }
        return CurrentUserContext.currentUser().orElse(AuditingConfig.SYSTEM_USER);
    }

    /** Due = a past due date not yet assessed (OVERDUE) or a due date landing exactly on the run date. */
    private static boolean isDue(MonitoringTiming timing, LocalDate runDate) {
        return timing.status() == TimingStatus.OVERDUE
                || (timing.nextDueDate() != null && timing.nextDueDate().equals(runDate));
    }

    private MonitoringTiming timingFor(AppliedCovenant ac, Instant lastAssessedAt, LocalDate runDate,
                                       ZoneId zone) {
        String cron = MonitoringTimingCalculator.effectiveCron(ac.getFrequency(), ac.getCronExpression());
        return MonitoringTimingCalculator.compute(
                cron, ac.getStartDate(), ac.getEndDate(), lastAssessedAt, runDate, zone);
    }

    /**
     * The last {@code executedAt} per applied covenant in {@code covenants}, resolved in one grouped
     * query. Covenants with no executed assessment are absent from the map (→ never assessed).
     */
    private Map<String, Instant> latestExecutedAtFor(List<AppliedCovenant> covenants) {
        if (covenants.isEmpty()) {
            return Map.of();
        }
        List<String> ids = new ArrayList<>(covenants.size());
        for (AppliedCovenant ac : covenants) {
            ids.add(ac.getId());
        }
        Map<String, Instant> lastExecuted = new HashMap<>();
        for (AppliedCovenantLastExecuted row : assessmentRepo.findLatestExecutedAtByAppliedCovenantIdIn(ids)) {
            if (row.getLastExecutedAt() != null) {
                lastExecuted.put(row.getAppliedCovenantId(), row.getLastExecutedAt());
            }
        }
        return lastExecuted;
    }

    private static String email(CpUser user) {
        if (user == null || user.email() == null || user.email().isBlank()) {
            return null;
        }
        return user.email();
    }

    private static String errorOf(NotificationResult result) {
        if (result == null) {
            return "No response from notification service";
        }
        return result.error() != null ? result.error() : "Notification service returned non-2xx";
    }

    private static String serialise(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Merge key: two CPs land in the same email iff they share BOTH the RM and WRM email. */
    private record RecipientKey(String rmEmail, String wrmEmail) {
        RecipientKey {
            Objects.requireNonNull(rmEmail, "rmEmail");
        }
    }

    /** Accumulates the CPs (and their monitoring rows) that share one (RM, WRM) pair. */
    private static final class PairGroup {
        private final String rmEmail;
        private final String wrmEmail;
        private final List<String> cpIds = new ArrayList<>();
        private final List<Map<String, String>> rows = new ArrayList<>();

        PairGroup(String rmEmail, String wrmEmail) {
            this.rmEmail = rmEmail;
            this.wrmEmail = wrmEmail;
        }

        void add(String cpId, CpMonitoringSummary summary) {
            cpIds.add(cpId);
            rows.add(row(cpId, summary));
        }

        String rmEmail() { return rmEmail; }
        String wrmEmail() { return wrmEmail; }
        List<String> cpIds() { return cpIds; }
        List<Map<String, String>> rows() { return rows; }

        /**
         * The {@code variables.cpMonitoring[]} entry for one CP. Every external key string lives here
         * (mirroring the CP Monitoring screen columns) so they can be renamed to match the
         * {@code Cov_Template} contract without touching any run logic. {@code LinkedHashMap} preserves
         * key order.
         */
        private static Map<String, String> row(String cpId, CpMonitoringSummary s) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("CP_ID", cpId);
            row.put("Applied", Long.toString(s.applied()));
            row.put("Compliant", Long.toString(s.compliant()));
            row.put("Breached", Long.toString(s.breached()));
            row.put("Error", Long.toString(s.error()));
            row.put("Pending", Long.toString(s.pendingReview()));
            return row;
        }
    }
}
