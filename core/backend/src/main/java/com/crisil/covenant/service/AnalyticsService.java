package com.crisil.covenant.service;

import com.crisil.covenant.domain.CpMonitoringSummary;
import com.crisil.covenant.dto.PortfolioAnalytics.Group;
import com.crisil.covenant.dto.PortfolioAnalytics;
import com.crisil.covenant.entity.CreditProposalCacheEntry;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import com.crisil.covenant.util.IdBatches;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /** Units the CP service gives facility amounts in, and how many crore one unit is. */
    static final Map<String, Double> TO_CRORE = Map.of("INR_CRORE", 1.0, "INR_LAKH", 0.01, "INR", 1e-7);

    private static final ObjectMapper JSON = new ObjectMapper();

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
        Map<String, Double> exposure = new HashMap<>();
        cache.forEach((cp, e) -> {
            Double crore = exposureCrore(e.getAttributesJson());
            if (crore != null) {
                exposure.put(cp, crore);
            }
        });
        return new PortfolioAnalytics(group("ALL", rows, exposure), groups(rows, segment, exposure),
                groups(rows, sector, exposure));
    }

    /** The CP's facility amount in INR crore, or null when it is missing or in an unknown unit. */
    static Double exposureCrore(String attributesJson) {
        if (attributesJson == null || attributesJson.isBlank()) {
            return null;
        }
        try {
            JsonNode a = JSON.readTree(attributesJson);
            JsonNode amount = a.get("facilityAmount");
            if (amount == null || amount.isNull()) {
                return null;
            }
            // The cache keeps attribute values as text ("15000"); the CP service may also send a number.
            double value = amount.isNumber() ? amount.asDouble()
                    : Double.parseDouble(amount.asText().replace(",", "").trim());
            Double toCrore = TO_CRORE.get(a.path("facilityAmountUnit").asText("INR_CRORE"));
            return toCrore == null ? null : value * toCrore;
        } catch (JsonProcessingException | NumberFormatException e) {
            return null;
        }
    }

    private static String keyOf(CreditProposalCacheEntry e, boolean segment) {
        String v = e == null ? null : segment ? e.getBusinessSegment() : e.getSector();
        return v == null || v.isBlank() ? UNKNOWN : v;
    }

    private static List<Group> groups(List<CpMonitoringSummary> rows, Function<CpMonitoringSummary, String> key,
                                      Map<String, Double> exposure) {
        Map<String, List<CpMonitoringSummary>> grouped = new TreeMap<>();
        for (CpMonitoringSummary r : rows) {
            grouped.computeIfAbsent(key.apply(r), k -> new ArrayList<>()).add(r);
        }
        List<Group> out = new ArrayList<>();
        grouped.forEach((k, v) -> out.add(group(k, v, exposure)));
        out.sort(Comparator.comparingDouble(Group::breachRate).reversed().thenComparing(Group::key));
        return out;
    }

    static Group group(String key, List<CpMonitoringSummary> rows, Map<String, Double> exposure) {
        double crore = 0;
        long withExposure = 0;
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
            Double cp = exposure.get(r.cpId());
            if (cp != null) {
                crore += cp;
                withExposure++;
            }
        }
        long assessed = compliant + breached;
        double rate = assessed == 0 ? 0.0 : Math.round(breached * 10000.0 / assessed) / 10000.0;
        return new Group(key, rows.size(), applied, compliant, breached, error, dispositioned, rate,
                withExposure == 0 ? null : Math.round(crore * 100) / 100.0, withExposure);
    }
}
