package com.crisil.covenant.service;

import com.crisil.covenant.dto.BreachReportRow;
import com.crisil.covenant.dto.NotificationRequest;
import com.crisil.covenant.dto.NotificationResult;
import com.crisil.covenant.entity.NotificationAudit;
import com.crisil.covenant.repository.NotificationAuditRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * FR-31: breaches left unhandled longer than {@code covenant.escalation.after-days} are escalated to the
 * credit head (or whoever {@code covenant.escalation.recipients} names) in one daily digest through the
 * notification service, template {@value #TEMPLATE}. Runs inside the daily job; switched off while no
 * recipient is configured. Each send is audited like the RM summaries, so Support sees it with the rest.
 */
@Service
public class EscalationService {

    static final String TEMPLATE = "Cov_Escalation";
    private static final Logger LOG = LoggerFactory.getLogger(EscalationService.class);
    private static final int BODY_MAX = 4000;

    private final MonitoringService monitoringService;
    private final BreachReportService breaches;
    private final NotificationClient notificationClient;
    private final NotificationAuditRepository auditRepo;
    private final ObjectMapper mapper;
    private final String recipients;
    private final int afterDays;

    public EscalationService(MonitoringService monitoringService, BreachReportService breaches,
                             NotificationClient notificationClient, NotificationAuditRepository auditRepo,
                             ObjectMapper mapper,
                             @Value("${covenant.escalation.recipients:}") String recipients,
                             @Value("${covenant.escalation.after-days:14}") int afterDays) {
        this.monitoringService = monitoringService;
        this.breaches = breaches;
        this.notificationClient = notificationClient;
        this.auditRepo = auditRepo;
        this.mapper = mapper;
        this.recipients = recipients == null ? "" : recipients.trim();
        this.afterDays = afterDays;
    }

    /** Sends the digest when there is something to escalate; returns how many breaches it listed. */
    public int run(String actor) {
        if (recipients.isEmpty()) {
            return 0;
        }
        List<BreachReportRow> due = breaches.openBreachesFor(monitoringService.allSummary(), afterDays).stream()
                .filter(BreachReportRow::escalate).toList();
        if (due.isEmpty()) {
            return 0;
        }
        List<Map<String, String>> rows = due.stream().map(EscalationService::row).toList();
        NotificationRequest request = new NotificationRequest("COV", TEMPLATE, recipients, null,
                new NotificationRequest.Variables(rows), "HIGH");
        long start = System.currentTimeMillis();
        NotificationResult result = notificationClient.send(request);
        boolean success = result != null && result.success();
        auditRepo.save(new NotificationAudit("escalation-" + UUID.randomUUID(), recipients, null,
                truncate(String.join(",", due.stream().map(BreachReportRow::cpId).distinct().toList())),
                truncate(json(request)), truncate(result == null ? null : result.responseBody()),
                result == null ? null : result.httpStatus(),
                success ? NotificationAudit.Outcome.SUCCESS : NotificationAudit.Outcome.FAILED,
                success ? null : "Escalation notification failed", System.currentTimeMillis() - start, actor,
                Instant.now()));
        LOG.info("Escalation digest: {} breach(es), sent={}", due.size(), success);
        return due.size();
    }

    private static Map<String, String> row(BreachReportRow r) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("CP_ID", r.cpId());
        m.put("Covenant", r.covenantName());
        m.put("Result", r.result() == null ? "" : r.result().name());
        m.put("Value", r.computedValue() == null ? "" : r.computedValue());
        m.put("Open_Since", r.since() == null ? "" : r.since().toString());
        m.put("Days_Open", String.valueOf(r.daysOpen()));
        m.put("Decision", r.status() == null ? "" : r.status().name());
        return m;
    }

    private String json(NotificationRequest request) {
        try {
            return mapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String truncate(String value) {
        return value == null || value.length() <= BODY_MAX ? value : value.substring(0, BODY_MAX);
    }
}
