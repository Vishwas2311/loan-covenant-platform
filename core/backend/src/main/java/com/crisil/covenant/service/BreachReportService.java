package com.crisil.covenant.service;

import com.crisil.covenant.domain.CpMonitoringSummary;
import com.crisil.covenant.dto.BreachReportRow;
import com.crisil.covenant.dto.CpReviewRow;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.repository.AssessmentRepository;
import com.crisil.covenant.util.TextPdfWriter;
import com.crisil.covenant.util.XlsxWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * FR-31/FR-32: open breaches and data errors across the caller's portfolio, how long each has been
 * open, and which should be escalated (open {@code escalationDays} or more without an approved
 * waiver/extension). Also produced as an Excel management report.
 */
@Service
public class BreachReportService {

    static final int HISTORY_DEPTH = 60;

    private final MonitoringService monitoringService;
    private final ReviewService reviewService;
    private final AssessmentRepository assessmentRepo;
    private final Clock clock;

    public BreachReportService(MonitoringService monitoringService, ReviewService reviewService,
                               AssessmentRepository assessmentRepo, Clock clock) {
        this.monitoringService = monitoringService;
        this.reviewService = reviewService;
        this.assessmentRepo = assessmentRepo;
        this.clock = clock;
    }

    public List<BreachReportRow> openBreaches(String user, int escalationDays) {
        LocalDate today = LocalDate.now(clock);
        List<BreachReportRow> out = new ArrayList<>();
        for (CpMonitoringSummary cp : monitoringService.summary(user)) {
            if (cp.breached() == 0 && cp.error() == 0) {
                continue;
            }
            for (CpReviewRow r : reviewService.reviewRows(cp.cpId())) {
                if (r.latestResult() != AssessmentResult.BREACHED && r.latestResult() != AssessmentResult.ERROR) {
                    continue;
                }
                LocalDate since = openSince(r.appliedCovenantId(), r.latestResult(), today);
                long days = ChronoUnit.DAYS.between(since, today);
                boolean handled = r.dispositionStatus() == CovenantDispositionStatus.WAIVER
                        || r.dispositionStatus() == CovenantDispositionStatus.EXTENSION;
                out.add(new BreachReportRow(cp.cpId(), r.appliedCovenantId(), r.covenantName(), r.latestResult(),
                        r.computedValue(), since, days, r.dispositionStatus(), r.approvalStatus(),
                        !handled && days >= escalationDays));
            }
        }
        out.sort(Comparator.comparing(BreachReportRow::escalate).reversed()
                .thenComparing(Comparator.comparingLong(BreachReportRow::daysOpen).reversed()));
        return out;
    }

    /** The first day of the latest unbroken run of {@code result} for the covenant. */
    LocalDate openSince(String appliedId, AssessmentResult result, LocalDate today) {
        LocalDate since = today;
        for (Assessment a : assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(appliedId,
                PageRequest.of(0, HISTORY_DEPTH))) {
            if (a.getResult() != result) {
                break;
            }
            Instant when = a.getExecutedAt() != null ? a.getExecutedAt() : a.getScheduledAt();
            if (when != null) {
                since = LocalDate.ofInstant(when, ZoneOffset.UTC);
            }
        }
        return since;
    }

    public byte[] openBreachesPdf(String user, int escalationDays) {
        List<BreachReportRow> rows = openBreaches(user, escalationDays);
        TextPdfWriter pdf = new TextPdfWriter("Open covenant breaches - " + LocalDate.now(clock)
                + " (escalate after " + escalationDays + " days)");
        pdf.line(String.format("%-14s %-36s %-9s %-11s %5s %-10s %-10s %s", "CP", "Covenant", "Result", "Since",
                "Days", "Status", "Approval", "Escalate"));
        for (BreachReportRow r : rows) {
            pdf.line(String.format("%-14.14s %-36.36s %-9s %-11s %5d %-10s %-10s %s", r.cpId(), r.covenantName(),
                    r.result(), r.since(), r.daysOpen(), r.status(), r.approval() == null ? "" : r.approval(),
                    r.escalate() ? "YES" : ""));
        }
        pdf.line("");
        pdf.line(rows.size() + " open item(s); " + rows.stream().filter(BreachReportRow::escalate).count()
                + " to escalate.");
        return pdf.toBytes();
    }

    public byte[] openBreachesXlsx(String user, int escalationDays) {
        XlsxWriter x = new XlsxWriter("Open breaches");
        x.row(List.of("CP", "Covenant", "Result", "Actual", "Open since", "Days open", "Status", "Approval",
                "Escalate"));
        for (BreachReportRow r : openBreaches(user, escalationDays)) {
            x.row(Arrays.asList(r.cpId(), r.covenantName(), String.valueOf(r.result()),
                    r.computedValue() == null ? "" : r.computedValue(), r.since().toString(), r.daysOpen(),
                    String.valueOf(r.status()), r.approval() == null ? "" : r.approval().name(),
                    r.escalate() ? "YES" : ""));
        }
        return x.toBytes();
    }
}
