package com.crisil.covenant.service;

import com.crisil.covenant.domain.CpMonitoringSummary;
import com.crisil.covenant.dto.PortfolioAnalytics.Group;
import com.crisil.covenant.dto.PortfolioAnalytics;
import com.crisil.covenant.entity.CreditProposalCacheEntry;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import com.crisil.covenant.util.IdBatches;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** FR-25: portfolio analytics built on the per-CP monitoring summary and the cached CP attributes. */
@Service
public class AnalyticsService {

    static final String UNKNOWN = "UNKNOWN";

    private final MonitoringService monitoringService;
    private final CreditProposalCacheRepository cacheRepo;

    public AnalyticsService(MonitoringService monitoringService, CreditProposalCacheRepository cacheRepo) {
        this.monitoringService = monitoringService;
        this.cacheRepo = cacheRepo;
    }

    public PortfolioAnalytics portfolio(String user) {
        List<CpMonitoringSummary> rows = monitoringService.summary(user);
        Map<String, CreditProposalCacheEntry> cache = new HashMap<>();
        for (CreditProposalCacheEntry e : IdBatches.fetch(rows.stream().map(CpMonitoringSummary::cpId).toList(), cacheRepo::findAllById)) {
            cache.put(e.getCpId(), e);
        }
        Function<CpMonitoringSummary, String> segment = r -> keyOf(cache.get(r.cpId()), true);
        Function<CpMonitoringSummary, String> sector = r -> keyOf(cache.get(r.cpId()), false);
        return new PortfolioAnalytics(group("ALL", rows), groups(rows, segment), groups(rows, sector));
    }

    private static String keyOf(CreditProposalCacheEntry e, boolean segment) {
        String v = e == null ? null : segment ? e.getBusinessSegment() : e.getSector();
        return v == null || v.isBlank() ? UNKNOWN : v;
    }

    private static List<Group> groups(List<CpMonitoringSummary> rows, Function<CpMonitoringSummary, String> key) {
        Map<String, List<CpMonitoringSummary>> grouped = new TreeMap<>();
        for (CpMonitoringSummary r : rows) {
            grouped.computeIfAbsent(key.apply(r), k -> new ArrayList<>()).add(r);
        }
        List<Group> out = new ArrayList<>();
        grouped.forEach((k, v) -> out.add(group(k, v)));
        out.sort(Comparator.comparingDouble(Group::breachRate).reversed().thenComparing(Group::key));
        return out;
    }

    static Group group(String key, List<CpMonitoringSummary> rows) {
        long applied = 0;
        long compliant = 0;
        long breached = 0;
        long error = 0;
        long dispositioned = 0;
        for (CpMonitoringSummary r : rows) {
            applied += r.applied();
            compliant += r.compliant();
            breached += r.breached();
            error += r.error();
            dispositioned += r.dispositioned();
        }
        long assessed = compliant + breached;
        double rate = assessed == 0 ? 0.0 : Math.round(breached * 10000.0 / assessed) / 10000.0;
        return new Group(key, rows.size(), applied, compliant, breached, error, dispositioned, rate);
    }
}
