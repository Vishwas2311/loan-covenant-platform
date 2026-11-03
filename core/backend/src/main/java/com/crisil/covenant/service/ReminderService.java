package com.crisil.covenant.service;

import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.dto.BorrowerContactRequest;
import com.crisil.covenant.dto.CertificateRequirementView;
import com.crisil.covenant.dto.NotificationRequest;
import com.crisil.covenant.dto.NotificationResult;
import com.crisil.covenant.dto.ReminderRunResult;
import com.crisil.covenant.entity.BorrowerContact;
import com.crisil.covenant.entity.NotificationAudit;
import com.crisil.covenant.entity.ReminderLog;
import com.crisil.covenant.repository.BorrowerContactRepository;
import com.crisil.covenant.repository.NotificationAuditRepository;
import com.crisil.covenant.repository.ReminderLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C2: e-mails the borrower about compliance certificates. Once, a set number of days before a
 * certificate is due; then, while it is overdue, at most once a week. Every e-mail goes through the
 * bank's notification service, is written to the sent-e-mail audit, and to the CP's reminder log, so
 * a re-run on the same day sends nothing twice.
 */
@Service
public class ReminderService {

    private static final Logger LOG = LoggerFactory.getLogger(ReminderService.class);
    static final String TEMPLATE = "Cov_Borrower_Certificate_Reminder";
    static final int OVERDUE_EVERY_DAYS = 7;

    private final BorrowerContactRepository contacts;
    private final ReminderLogRepository log;
    private final CertificateRequirementService requirements;
    private final NotificationClient notificationClient;
    private final NotificationAuditRepository auditRepo;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ReminderService(BorrowerContactRepository contacts, ReminderLogRepository log,
                           CertificateRequirementService requirements, NotificationClient notificationClient,
                           NotificationAuditRepository auditRepo, ObjectMapper mapper, Clock clock) {
        this.contacts = contacts;
        this.log = log;
        this.requirements = requirements;
        this.notificationClient = notificationClient;
        this.auditRepo = auditRepo;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<BorrowerContact> contact(String cpId) {
        return contacts.findById(cpId);
    }

    @Transactional
    public BorrowerContact saveContact(String cpId, BorrowerContactRequest req) {
        BorrowerContact c = contacts.findById(cpId).orElseGet(() -> new BorrowerContact(cpId));
        c.setContactName(req.contactName().trim());
        c.setEmail(req.email().trim());
        c.setRemindersOn(req.remindersOn());
        c.setDaysBefore(req.daysBefore());
        c.touched(CurrentUserContext.currentUser().orElse("system"), Instant.now(clock));
        return contacts.save(c);
    }

    @Transactional(readOnly = true)
    public List<ReminderLog> logFor(String cpId) {
        return log.findByCpIdOrderByCreatedAtDesc(cpId);
    }

    /** Which reminder, if any, a requirement needs today. */
    Optional<ReminderLog.Kind> due(CertificateRequirementView q, BorrowerContact c, LocalDate today) {
        if (q.state() == CertificateRequirementView.State.OVERDUE) {
            boolean recent = log.existsByRequirementIdAndKindAndSentOnAfter(
                    q.id(), ReminderLog.Kind.OVERDUE, today.minusDays(OVERDUE_EVERY_DAYS));
            return recent ? Optional.empty() : Optional.of(ReminderLog.Kind.OVERDUE);
        }
        if (q.state() == CertificateRequirementView.State.PENDING && q.dueDate() != null
                && !today.isBefore(q.dueDate().minusDays(c.getDaysBefore())) && !today.isAfter(q.dueDate())
                && !log.existsByRequirementIdAndKind(q.id(), ReminderLog.Kind.DUE_SOON)) {
            return Optional.of(ReminderLog.Kind.DUE_SOON);
        }
        return Optional.empty();
    }

    /** Send today's reminders for every CP whose borrower contact has them switched on. */
    @Transactional
    public ReminderRunResult run(String actor) {
        LocalDate today = LocalDate.now(clock);
        String runId = "reminders-" + UUID.randomUUID();
        int sent = 0;
        int failed = 0;
        List<BorrowerContact> active = contacts.findByRemindersOnTrue();
        for (BorrowerContact c : active) {
            for (CertificateRequirementView q : requirements.forCp(c.getCpId())) {
                Optional<ReminderLog.Kind> kind = due(q, c, today);
                if (kind.isEmpty()) {
                    continue;
                }
                if (send(runId, actor, c, q, kind.get(), today)) {
                    sent++;
                } else {
                    failed++;
                }
            }
        }
        LOG.info("Borrower reminders: contacts={}, sent={}, failed={}", active.size(), sent, failed);
        return new ReminderRunResult(active.size(), sent, failed);
    }

    private boolean send(String runId, String actor, BorrowerContact c, CertificateRequirementView q,
                         ReminderLog.Kind kind, LocalDate today) {
        Map<String, String> vars = Map.of(
                "cpId", c.getCpId(), "contactName", c.getContactName(), "kind", kind.name(),
                "periodEnd", String.valueOf(q.periodEnd()), "dueDate", String.valueOf(q.dueDate()),
                "overdueDays", String.valueOf(q.overdueDays()));
        NotificationRequest request = new NotificationRequest("COV", TEMPLATE, c.getEmail(), null,
                new NotificationRequest.Variables(List.of(vars)), kind == ReminderLog.Kind.OVERDUE ? "HIGH" : "NORMAL");
        NotificationResult result = notificationClient.send(request);
        boolean ok = result != null && result.success();
        String error = ok ? null : result == null ? "No response" : String.valueOf(result.error());
        auditRepo.save(new NotificationAudit(runId, c.getEmail(), null, c.getCpId(), cut(json(request), 4000),
                cut(result == null ? null : result.responseBody(), 4000), result == null ? null : result.httpStatus(),
                ok ? NotificationAudit.Outcome.SUCCESS : NotificationAudit.Outcome.FAILED, cut(error, 512), null, actor,
                Instant.now(clock)));
        log.save(new ReminderLog(c.getCpId(), q.id(), kind, today, c.getEmail(), ok ? "SENT" : "FAILED", cut(error, 1000),
                Instant.now(clock)));
        return ok;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
