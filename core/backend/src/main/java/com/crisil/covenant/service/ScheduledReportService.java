package com.crisil.covenant.service;

import com.crisil.covenant.dto.CpReviewRow;
import com.crisil.covenant.dto.NotificationRequest;
import com.crisil.covenant.dto.NotificationResult;
import com.crisil.covenant.dto.ReportDefinition;
import com.crisil.covenant.dto.SavedReportRequest;
import com.crisil.covenant.dto.SavedReportView;
import com.crisil.covenant.entity.CreditProposalCacheEntry;
import com.crisil.covenant.entity.NotificationAudit;
import com.crisil.covenant.entity.SavedReport;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import com.crisil.covenant.repository.NotificationAuditRepository;
import com.crisil.covenant.repository.SavedReportRepository;
import com.crisil.covenant.util.IdBatches;
import com.crisil.covenant.util.XlsxWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * C11: custom reports. A report is the CP workspace's covenant rows across the owner's CPs, filtered
 * and cut down to the columns they chose. It can be run on screen, downloaded as Excel, saved, and
 * e-mailed daily, weekly or monthly from the daily sweep (as rows in the e-mail; the notification
 * service takes no attachments).
 */
@Service
public class ScheduledReportService {

    /** Rows put into one e-mail at most; the full list is always in the Excel download. */
    static final int EMAIL_ROWS = 200;

    private final MonitoringService monitoringService;
    private final ReviewService reviewService;
    private final CreditProposalCacheRepository cacheRepo;
    private final SavedReportRepository repo;
    private final NotificationClient notificationClient;
    private final NotificationAuditRepository auditRepo;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ScheduledReportService(MonitoringService monitoringService, ReviewService reviewService,
                                  CreditProposalCacheRepository cacheRepo, SavedReportRepository repo,
                                  NotificationClient notificationClient, NotificationAuditRepository auditRepo,
                                  ObjectMapper mapper, Clock clock) {
        this.monitoringService = monitoringService;
        this.reviewService = reviewService;
        this.cacheRepo = cacheRepo;
        this.repo = repo;
        this.notificationClient = notificationClient;
        this.auditRepo = auditRepo;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- running

    /** The report's rows for {@code user}'s CPs, each a column → text map in the chosen order. */
    @Transactional(readOnly = true)
    public List<Map<String, String>> run(String user, ReportDefinition def) {
        List<String> cps = monitoringService.visibleCpIds(user);
        Map<String, String> segment = new HashMap<>();
        for (CreditProposalCacheEntry e : IdBatches.fetch(cps, cacheRepo::findAllById)) {
            segment.put(e.getCpId(), e.getBusinessSegment());
        }
        String cpFilter = def.cpFilter() == null ? "" : def.cpFilter().trim();
        List<String> results = def.results() == null ? List.of() : def.results();
        List<Map<String, String>> out = new ArrayList<>();
        for (String cp : cps) {
            if (!containsIgnoreCase(cp, cpFilter)) {
                continue;
            }
            if (def.segment() != null && !def.segment().isBlank()
                    && !sameIgnoringCase(def.segment().trim(), segment.getOrDefault(cp, ""))) {
                continue;
            }
            for (CpReviewRow r : reviewService.reviewRows(cp)) {
                if (!results.isEmpty() && !results.contains(String.valueOf(r.latestResult()))
                        && !(r.atRisk() && results.contains("AT_RISK"))) {
                    continue;
                }
                out.add(cells(cp, segment.get(cp), r, def.chosenColumns()));
            }
        }
        return out;
    }

    static Map<String, String> cells(String cp, String segment, CpReviewRow r, List<String> columns) {
        Map<String, String> all = new HashMap<>();
        all.put("cpId", cp);
        all.put("segment", text(segment));
        all.put("covenantName", r.covenantName());
        all.put("rule", text(r.conditionExpression()));
        all.put("value", text(r.computedValue()));
        all.put("headroom", r.headroom() == null ? "" : r.headroom().toPlainString());
        all.put("result", r.latestResult() == null ? "NOT ASSESSED" : r.latestResult().name());
        all.put("atRisk", r.atRisk() ? "YES" : "");
        all.put("status", text(r.dispositionStatus()));
        all.put("approval", text(r.approvalStatus()));
        all.put("nextDueDate", text(r.nextDueDate()));
        all.put("overdueDays", r.overdueByDays() > 0 ? String.valueOf(r.overdueByDays()) : "");
        Map<String, String> row = new LinkedHashMap<>();
        columns.forEach(c -> row.put(c, all.get(c)));
        return row;
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString();
    }

    /** The report as an Excel sheet, header row first. */
    public byte[] xlsx(String name, List<String> columns, List<Map<String, String>> rows) {
        XlsxWriter x = new XlsxWriter(name.length() > 31 ? name.substring(0, 31) : name);
        x.row(new ArrayList<>(columns));
        for (Map<String, String> r : rows) {
            x.row(new ArrayList<>(r.values()));
        }
        return x.toBytes();
    }

    // ---------------------------------------------------------------- saving

    @Transactional(readOnly = true)
    public List<SavedReportView> list(String owner) {
        return repo.findByOwnerOrderByNameAsc(owner).stream().map(this::view).toList();
    }

    @Transactional
    public SavedReportView create(String owner, SavedReportRequest req) {
        SavedReport r = new SavedReport(owner, Instant.now(clock));
        apply(r, req);
        return view(repo.save(r));
    }

    @Transactional
    public SavedReportView update(String owner, String id, SavedReportRequest req) {
        SavedReport r = owned(owner, id);
        apply(r, req);
        return view(repo.save(r));
    }

    @Transactional
    public void delete(String owner, String id) {
        repo.delete(owned(owner, id));
    }

    @Transactional(readOnly = true)
    public SavedReport owned(String owner, String id) {
        SavedReport r = repo.findById(id).orElse(null);
        if (r == null || !Objects.equals(r.getOwner(), owner)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Report " + id + " not found");
        }
        return r;
    }

    private void apply(SavedReport r, SavedReportRequest req) {
        String emails = req.emailTo() == null ? null : req.emailTo().trim();
        if (req.schedule() != SavedReport.Schedule.NONE && (emails == null || emails.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A scheduled report needs an e-mail address");
        }
        if (emails != null && !emails.isEmpty() && !validAddressList(emails)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "E-mail addresses must be separated by commas");
        }
        r.setName(req.name().trim());
        r.setDefinitionJson(json(req.definition()));
        r.setSchedule(req.schedule());
        r.setEmailTo(emails == null || emails.isEmpty() ? null : emails);
    }

    /** Comma-separated addresses, each with one @, something either side, and no spaces. */
    static boolean validAddressList(String emails) {
        for (String part : emails.split(",", -1)) {
            String a = part.trim();
            int at = a.indexOf('@');
            if (a.isEmpty() || a.length() > 254 || at <= 0 || at != a.lastIndexOf('@') || at == a.length() - 1
                    || a.chars().anyMatch(Character::isWhitespace)) {
                return false;
            }
        }
        return true;
    }

    /** Case-insensitive "contains" without case-mapping the strings (CP ids are plain ASCII). */
    static boolean containsIgnoreCase(String text, String part) {
        if (part.isEmpty()) {
            return true;
        }
        for (int i = 0; i + part.length() <= text.length(); i++) {
            int j = 0;
            while (j < part.length() && ascii(text.charAt(i + j)) == ascii(part.charAt(j))) {
                j++;
            }
            if (j == part.length()) {
                return true;
            }
        }
        return false;
    }

    static boolean sameIgnoringCase(String a, String b) {
        return b != null && a.length() == b.length() && containsIgnoreCase(a, b);
    }

    private static char ascii(char c) {
        return c >= 'a' && c <= 'z' ? (char) (c - ('a' - 'A')) : c;
    }

    public ReportDefinition definition(SavedReport r) {
        try {
            return mapper.readValue(r.getDefinitionJson(), ReportDefinition.class);
        } catch (JsonProcessingException e) {
            return new ReportDefinition(List.of(), List.of(), null, null);
        }
    }

    private SavedReportView view(SavedReport r) {
        return new SavedReportView(r.getId(), r.getName(), definition(r), r.getSchedule().name(), r.getEmailTo(),
                r.getLastSentOn());
    }

    // ---------------------------------------------------------------- scheduling

    /** Whether a report on {@code schedule} last sent on {@code last} is due on {@code today}. */
    static boolean isDue(SavedReport.Schedule schedule, LocalDate last, LocalDate today) {
        if (schedule == SavedReport.Schedule.NONE) {
            return false;
        }
        if (last == null) {
            return true;
        }
        return switch (schedule) {
            case DAILY -> last.isBefore(today);
            case WEEKLY -> !last.plusWeeks(1).isAfter(today);
            case MONTHLY -> !last.plusMonths(1).isAfter(today);
            case NONE -> false;
        };
    }

    /** E-mail every scheduled report that is due today. Returns how many were sent. */
    @Transactional
    public int sendDue(String actor) {
        LocalDate today = LocalDate.now(clock);
        String runId = "reports-" + UUID.randomUUID();
        int sent = 0;
        for (SavedReport r : repo.findByScheduleNot(SavedReport.Schedule.NONE)) {
            if (!isDue(r.getSchedule(), r.getLastSentOn(), today) || r.getEmailTo() == null) {
                continue;
            }
            List<Map<String, String>> rows = run(r.getOwner(), definition(r));
            List<Map<String, String>> body = new ArrayList<>();
            body.add(Map.of("report", r.getName(), "rows", String.valueOf(rows.size()), "date", today.toString()));
            body.addAll(rows.subList(0, Math.min(EMAIL_ROWS, rows.size())));
            NotificationRequest request = new NotificationRequest("COV", "Cov_Saved_Report", r.getEmailTo(), null,
                    new NotificationRequest.Variables(body), "NORMAL");
            NotificationResult result = notificationClient.send(request);
            boolean ok = result != null && result.success();
            auditRepo.save(new NotificationAudit(runId, r.getEmailTo(), null, "report:" + r.getName(), cut(json(request), 4000),
                    cut(result == null ? null : result.responseBody(), 4000), result == null ? null : result.httpStatus(),
                    ok ? NotificationAudit.Outcome.SUCCESS : NotificationAudit.Outcome.FAILED,
                    cut(ok ? null : result == null ? "No response" : result.error(), 512), null, actor, Instant.now(clock)));
            if (ok) {
                r.setLastSentOn(today);
                repo.save(r);
                sent++;
            }
        }
        return sent;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise report data", e);
        }
    }

    static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
