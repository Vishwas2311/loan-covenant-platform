package com.crisil.covenant.service;

import com.crisil.covenant.domain.CovenantOnCp;
import com.crisil.covenant.dto.CovenantResponse;
import com.crisil.covenant.dto.PeriodComparison;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.repository.AssessmentRepository;
import com.crisil.covenant.util.XlsxWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** FR-24: period-over-period comparison of a CP's covenants, and Excel exports of it and of the library. */
@Service
public class ComparisonService {

    static final int MAX_PERIODS = 24;

    private final MonitoringService monitoringService;
    private final AssessmentRepository assessmentRepo;
    private final CovenantMasterService masterService;

    public ComparisonService(MonitoringService monitoringService, AssessmentRepository assessmentRepo,
                             CovenantMasterService masterService) {
        this.monitoringService = monitoringService;
        this.assessmentRepo = assessmentRepo;
        this.masterService = masterService;
    }

    public PeriodComparison compare(String cpId, int periods) {
        int n = Math.max(1, Math.min(periods, MAX_PERIODS));
        List<CovenantOnCp> covenants = monitoringService.covenantsForCp(cpId);
        // Latest assessment per (covenant, day); assessments come newest first.
        Map<String, Map<LocalDate, Assessment>> byCovenant = new HashMap<>();
        TreeSet<LocalDate> days = new TreeSet<>();
        for (Assessment a : assessmentRepo.findByAppliedCovenantCpIdOrderByScheduledAtDesc(cpId)) {
            Instant when = a.getExecutedAt() != null ? a.getExecutedAt() : a.getScheduledAt();
            if (when == null) {
                continue;
            }
            LocalDate day = LocalDate.ofInstant(when, ZoneOffset.UTC);
            byCovenant.computeIfAbsent(a.getAppliedCovenant().getId(), k -> new LinkedHashMap<>())
                    .putIfAbsent(day, a);
            days.add(day);
        }
        List<LocalDate> columns = new ArrayList<>(days.descendingSet()).subList(0, Math.min(n, days.size()));
        List<PeriodComparison.Row> rows = new ArrayList<>();
        for (CovenantOnCp c : covenants) {
            Map<LocalDate, Assessment> perDay = byCovenant.getOrDefault(c.appliedCovenantId(), Map.of());
            List<PeriodComparison.Cell> cells = new ArrayList<>();
            for (LocalDate day : columns) {
                Assessment a = perDay.get(day);
                cells.add(a == null ? null : new PeriodComparison.Cell(a.getResult(), a.getComputedValue(), a.getId()));
            }
            rows.add(new PeriodComparison.Row(c.appliedCovenantId(), c.covenantName(), c.conditionExpression(), cells,
                    trend(c.conditionExpression(), cells)));
        }
        return new PeriodComparison(cpId, List.copyOf(columns), rows);
    }

    /**
     * FR-26: newest vs oldest numeric actual. A change under 2% is STABLE; otherwise the rule's direction
     * decides: for a ceiling ({@code <}, {@code <=}) a fall is an improvement, for a floor a rise is.
     */
    static PeriodComparison.Trend trend(String condition, List<PeriodComparison.Cell> cellsNewestFirst) {
        List<Double> values = new ArrayList<>();
        for (PeriodComparison.Cell cell : cellsNewestFirst) {
            Double v = cell == null ? null : number(cell.computedValue());
            if (v != null) {
                values.add(v);
            }
        }
        if (values.size() < 2) {
            return null;
        }
        double newest = values.get(0);
        double oldest = values.get(values.size() - 1);
        double change = (newest - oldest) / Math.max(Math.abs(oldest), 1e-9);
        if (Math.abs(change) < 0.02) {
            return PeriodComparison.Trend.STABLE;
        }
        boolean lowerIsBetter = condition != null && condition.contains("<") && !condition.contains(">");
        boolean better = lowerIsBetter ? change < 0 : change > 0;
        return better ? PeriodComparison.Trend.IMPROVING : PeriodComparison.Trend.WORSENING;
    }

    private static Double number(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public byte[] compareXlsx(String cpId, int periods) {
        PeriodComparison cmp = compare(cpId, periods);
        XlsxWriter x = new XlsxWriter("CP " + cpId);
        List<Object> header = new ArrayList<>(List.of("Covenant", "Rule", "Trend"));
        cmp.periods().forEach(p -> header.add(p.toString()));
        x.row(header);
        for (PeriodComparison.Row r : cmp.rows()) {
            List<Object> line = new ArrayList<>(List.of(r.covenantName(), nullToEmpty(r.conditionExpression()),
                    r.trend() == null ? "" : r.trend().name()));
            for (PeriodComparison.Cell cell : r.cells()) {
                line.add(cell == null ? "" : cell.result() + (cell.computedValue() == null ? ""
                        : " (" + cell.computedValue() + ")"));
            }
            x.row(line);
        }
        return x.toBytes();
    }

    public byte[] libraryXlsx() {
        XlsxWriter x = new XlsxWriter("Covenant library");
        x.row(List.of("Name", "Category", "Type", "Rule", "Default frequency", "Active", "Description"));
        int page = 0;
        while (true) {
            var result = masterService.search(null, null, null, null, PageRequest.of(page, 200));
            for (CovenantResponse c : result.getContent()) {
                x.row(java.util.Arrays.asList(c.name(), String.valueOf(c.category()), String.valueOf(c.assessmentType()),
                        nullToEmpty(c.conditionExpression()), String.valueOf(c.defaultFrequency()),
                        c.active() ? "yes" : "no", nullToEmpty(c.description())));
            }
            if (result.isLast()) {
                return x.toBytes();
            }
            page++;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
