package com.crisil.covenant.service;

import com.crisil.covenant.domain.CreditProposalDetails;
import com.crisil.covenant.dto.CpCycleView;
import com.crisil.covenant.entity.CreditProposalCacheEntry;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * US-MON-23: resolves a CP's renewal lineage by walking parent links from the opened CP up to the
 * root, numbering each CP as a "cycle" (root = 1, opened CP = highest, flagged {@code current}).
 *
 * <p>Parent links are read <strong>cache-first</strong>
 * ({@code cov_credit_proposal_cache.parent_cp_id}, populated by {@link CachedCovenantAttributesService});
 * on a cache miss it falls back to a best-effort live CP lookup. The walk is bounded by a
 * {@code visited} set (breaks accidental cycles) and {@link #MAX_DEPTH} (caps pathological chains) and
 * never throws — an unreachable parent simply ends the chain (US-MON-23 AC10).
 */
@Service
public class CpLineageService {

    /** Hard cap on lineage length; guards against pathological/looping parent chains. */
    static final int MAX_DEPTH = 32;

    private static final Logger LOG = LoggerFactory.getLogger(CpLineageService.class);

    private final CreditProposalCacheRepository cacheRepo;
    private final CreditProposalClient creditProposalClient;

    public CpLineageService(CreditProposalCacheRepository cacheRepo,
                            CreditProposalClient creditProposalClient) {
        this.cacheRepo = cacheRepo;
        this.creditProposalClient = creditProposalClient;
    }

    /**
     * The lineage of {@code cpId} ordered oldest-first: Cycle 1 is the root ancestor and the passed CP
     * is the highest cycle, flagged {@code current}. A CP with no resolvable parent yields a single
     * (current) cycle; a blank id yields an empty list.
     */
    public List<CpCycleView> lineageFor(String cpId) {
        List<String> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String cur = cpId;
        // current -> root; visited.add(...) short-circuits an accidental loop, MAX_DEPTH caps length.
        while (cur != null && !cur.isBlank() && chain.size() < MAX_DEPTH && visited.add(cur)) {
            chain.add(cur);
            cur = parentOf(cur);
        }
        Collections.reverse(chain); // now root-first
        List<CpCycleView> cycles = new ArrayList<>(chain.size());
        for (int i = 0; i < chain.size(); i++) {
            String id = chain.get(i);
            cycles.add(new CpCycleView(i + 1, id, id.equals(cpId)));
        }
        return cycles;
    }

    /** Immediate parent of {@code cpId}: cache-first, then a best-effort live lookup; never throws. */
    private String parentOf(String cpId) {
        String cached = cacheRepo.findById(cpId)
                .map(CreditProposalCacheEntry::getParentCpId)
                .filter(p -> !p.isBlank())
                .orElse(null);
        if (cached != null) {
            return cached;
        }
        try {
            CreditProposalDetails details = creditProposalClient.lookup(cpId);
            if (details == null) {
                return null;
            }
            String parent = details.parentCpId();
            return (parent == null || parent.isBlank()) ? null : parent;
        } catch (RuntimeException ex) {
            LOG.warn("CP lineage lookup failed for {}: {}",
                    String.valueOf(cpId).replace('\n', '_').replace('\r', '_'),
                    String.valueOf(ex.getMessage()).replace('\n', '_').replace('\r', '_'));
            return null;
        }
    }
}
