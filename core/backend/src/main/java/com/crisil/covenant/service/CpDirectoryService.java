package com.crisil.covenant.service;

import com.crisil.covenant.domain.CpMonitoringSummary;
import com.crisil.covenant.domain.CreditProposalDetails;
import com.crisil.covenant.dto.CpCard;
import com.crisil.covenant.entity.CreditProposalCacheEntry;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
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
 * The CPs the caller looks after (RM or WRM), with the borrower name and covenant counts, so a page
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
        List<String> owned = cacheRepo.findCpIdsOwnedBy(user);
        if (owned.isEmpty()) {
            return List.of();
        }
        Map<String, CpMonitoringSummary> counts = monitoringService.summary(user).stream()
                .collect(Collectors.toMap(CpMonitoringSummary::cpId, Function.identity(), (a, b) -> a));
        List<CpCard> out = new ArrayList<>();
        for (CreditProposalCacheEntry e : cacheRepo.findAllById(owned)) {
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
        try {
            CreditProposalDetails d = creditProposals.lookup(cpId);
            if (d != null && d.name() != null) {
                names.put(cpId, d.name());
                return d.name();
            }
        } catch (RuntimeException ex) {
            LOG.warn("CP name lookup failed for {}: {}",
                    String.valueOf(cpId).replace('\n', '_').replace('\r', '_'),
                    String.valueOf(ex.getMessage()).replace('\n', '_').replace('\r', '_'));
        }
        return null;
    }
}
