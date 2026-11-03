package com.crisil.covenant.service;

import com.crisil.covenant.domain.CpMonitoringSummary;
import com.crisil.covenant.dto.ResultTrendPoint;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.repository.AssessmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** C9: check results per month over the CPs the user looks after, for the Reports charts. */
@Service
@Transactional(readOnly = true)
public class TrendService {

    private final MonitoringService monitoringService;
    private final AssessmentRepository assessmentRepo;
    private final Clock clock;

    public TrendService(MonitoringService monitoringService, AssessmentRepository assessmentRepo, Clock clock) {
        this.monitoringService = monitoringService;
        this.assessmentRepo = assessmentRepo;
        this.clock = clock;
    }

    /** The last {@code months} calendar months (UTC), oldest first; months with no checks show zeros. */
    public List<ResultTrendPoint> monthly(String user, int months) {
        YearMonth last = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        YearMonth first = last.minusMonths(months - 1L);
        Map<YearMonth, long[]> counts = new LinkedHashMap<>();
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
            counts.put(m, new long[4]);
        }
        Instant from = first.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        for (CpMonitoringSummary cp : monitoringService.summary(user)) {
            for (Assessment a : assessmentRepo.findByAppliedCovenantCpIdOrderByScheduledAtDesc(cp.cpId())) {
                Instant at = a.getExecutedAt();
                if (at == null || at.isBefore(from) || a.getResult() == null) {
                    continue;
                }
                long[] c = counts.get(YearMonth.from(at.atZone(ZoneOffset.UTC)));
                if (c == null) {
                    continue;
                }
                switch (a.getResult()) {
                    case COMPLIANT -> c[0]++;
                    case BREACHED -> c[1]++;
                    case ERROR -> c[2]++;
                    default -> { /* NA and PENDING_REVIEW only count toward the total */ }
                }
                c[3]++;
            }
        }
        List<ResultTrendPoint> out = new ArrayList<>();
        counts.forEach((m, c) -> out.add(new ResultTrendPoint(m.toString(), c[0], c[1], c[2], c[3])));
        return out;
    }
}
