package com.crisil.covenant.service;

import com.crisil.covenant.domain.CpMonitoringSummary;
import com.crisil.covenant.domain.CreditProposalDetails;
import com.crisil.covenant.dto.CpCard;
import com.crisil.covenant.entity.CreditProposalCacheEntry;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import com.crisil.covenant.util.IdBatches;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The CPs the caller looks after (RM or WRM; every CP for admin, auditor and support), with the borrower name and covenant counts, so a page
 * opened without a CP can offer a list instead of an empty id box. Borrower names come from the CP
 * service and are kept in memory: they do not change while the app runs, and a failed lookup is
 * retried next time rather than remembered.
 */
@Service
public class CpDirectoryService {

    private static final Logger LOG = LoggerFactory.getLogger(CpDirectoryService.class);

    private final CreditProposalCacheRepository cacheRepo;
    private final MonitoringService monitoringService;
    private final CreditProposalClient creditProposals;
    private final Map<String, String> names = new ConcurrentHashMap<>();
    /** CPs the CP service could not name, and when: not asked again for {@link #MISS_TTL}. */
    private final Map<String, Instant> misses = new ConcurrentHashMap<>();
    private static final Duration MISS_TTL = Duration.ofMinutes(10);

    public CpDirectoryService(CreditProposalCacheRepository cacheRepo, MonitoringService monitoringService,
                              CreditProposalClient creditProposals) {
        this.cacheRepo = cacheRepo;
        this.monitoringService = monitoringService;
        this.creditProposals = creditProposals;
    }

    public List<CpCard> cps(String user) {
        if (user == null || user.isBlank()) {
            return List.of();
        }
        List<String> owned = monitoringService.visibleCpIds(user);
        if (owned.isEmpty()) {
            return List.of();
        }
        Map<String, CpMonitoringSummary> counts = monitoringService.summary(user).stream()
                .collect(Collectors.toMap(CpMonitoringSummary::cpId, Function.identity(), (a, b) -> a));
        List<CpCard> out = new ArrayList<>();
        for (CreditProposalCacheEntry e : IdBatches.fetch(owned, cacheRepo::findAllById)) {
            CpMonitoringSummary c = counts.get(e.getCpId());
            out.add(c == null
                    ? new CpCard(e.getCpId(), name(e.getCpId()), e.getBusinessSegment(), e.getSector(),
                            e.getRmUserId(), 0, 0, 0, 0, 0, 0)
                    : new CpCard(e.getCpId(), name(e.getCpId()), e.getBusinessSegment(), e.getSector(),
                            e.getRmUserId(), c.applied(), c.compliant(), c.breached(), c.error(),
                            c.pendingReview(), c.dispositioned()));
        }
        out.sort(Comparator.comparing(CpCard::cpId));
        return out;
    }

    private String name(String cpId) {
        String known = names.get(cpId);
        if (known != null) {
            return known;
        }
        Instant missed = misses.get(cpId);
        if (missed != null && missed.plus(MISS_TTL).isAfter(Instant.now())) {
            return null; // a CP list of thousands must not call the CP service again for each unknown CP
        }
        try {
            CreditProposalDetails d = creditProposals.lookup(cpId);
            if (d != null && d.name() != null) {
                names.put(cpId, d.name());
                misses.remove(cpId);
                return d.name();
            }
        } catch (RuntimeException ex) {
            LOG.warn("CP name lookup failed for {}: {}",
                    String.valueOf(cpId).replace('\n', '_').replace('\r', '_'),
                    String.valueOf(ex.getMessage()).replace('\n', '_').replace('\r', '_'));
        }
        misses.put(cpId, Instant.now());
        return null;
    }
}
