package com.crisil.covenant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.crisil.covenant.domain.CovenantAttributes;
import com.crisil.covenant.domain.CpUser;
import com.crisil.covenant.domain.CreditProposalDetails;
import com.crisil.covenant.entity.CreditProposalCacheEntry;
import com.crisil.covenant.repository.CreditProposalCacheRepository;

/**
 * US-011: caches Attributes API responses for {@link #TTL} to avoid hammering the external service
 * during repeated recommendation runs in the wizard.
 */
@Service
@Transactional
public class CachedCovenantAttributesService {

    public static final Duration TTL = Duration.ofHours(1);
    private static final Logger LOG = LoggerFactory.getLogger(CachedCovenantAttributesService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> ATTR_TYPE = new TypeReference<>() {};

    private final CovenantAttributesClient client;
    private final CreditProposalCacheRepository cacheRepo;
    private final CreditProposalClient creditProposalClient;
    private final Clock clock;

    public CachedCovenantAttributesService(CovenantAttributesClient client,
                                           CreditProposalCacheRepository cacheRepo,
                                           CreditProposalClient creditProposalClient,
                                           Clock clock) {
        this.client = client;
        this.cacheRepo = cacheRepo;
        this.creditProposalClient = creditProposalClient;
        this.clock = clock;
    }

    public CovenantAttributes fetch(String cpId) {
        Optional<CreditProposalCacheEntry> cached = cacheRepo.findById(cpId);
        Instant now = Instant.now(clock);
        if (cached.isPresent() && isFresh(cached.get(), now)) {
            return toAttributes(cached.get());
        }
        CovenantAttributes fresh = client.fetch(cpId);
        save(cpId, fresh, now);
        return fresh;
    }

    /**
     * Makes sure {@code cpId} has a cache row. Portfolios, task lists and bank-wide views are built from this
     * cache, so a CP whose covenants were applied without the onboarding recommendation step (an import, the
     * API) would otherwise run, breach and e-mail without appearing in anyone's portfolio. A failed lookup is
     * logged and retried by the next fetch; it never fails the caller.
     */
    public void ensureCached(String cpId) {
        if (cpId == null || cpId.isBlank() || cacheRepo.existsById(cpId)) {
            return;
        }
        try {
            fetch(cpId);
        } catch (RuntimeException ex) {
            LOG.warn("Could not cache CP {} after applying covenants: {}",
                    cpId.replace('\n', '_').replace('\r', '_'),
                    String.valueOf(ex.getMessage()).replace('\n', '_').replace('\r', '_'));
        }
    }

    private boolean isFresh(CreditProposalCacheEntry entry, Instant now) {
        return Duration.between(entry.getFetchedAt(), now).compareTo(TTL) < 0;
    }

    private void save(String cpId, CovenantAttributes fresh, Instant now) {
        CreditProposalCacheEntry entry = cacheRepo.findById(cpId)
                .orElseGet(CreditProposalCacheEntry::new);
        entry.setCpId(cpId);
        entry.setBusinessSegment(fresh.businessSegment());
        entry.setSector(fresh.sector());
        try {
            entry.setAttributesJson(MAPPER.writeValueAsString(fresh.safeAttributes()));
        } catch (JsonProcessingException ex) {
            LOG.warn("Failed to serialise attributes for {}: {}",
                    String.valueOf(cpId).replace('\n', '_').replace('\r', '_'),
                    String.valueOf(ex.getMessage()).replace('\n', '_').replace('\r', '_'));
            entry.setAttributesJson("{}");
        }
        enrichOwnership(cpId, entry);
        entry.setFetchedAt(now);
        cacheRepo.save(entry);
    }

    /**
     * US-MON-09 / US-MON-15 portfolio scoping (+ US-MON-23 lineage): best-effort enrich the cache row
     * with the CP's RM/WRM ids and its {@code parentCpId} from the external CP service, so monitoring
     * reads can filter by owner and the cycle viewer can walk the renewal chain without a per-hop remote
     * call. This is deliberately defensive:
     * <ul>
     *   <li>a failed lookup never fails the attributes fetch (the primary purpose of {@code save});</li>
     *   <li>a {@code null} rm/wrm/parent (e.g. a demo stub that omits them) <em>preserves</em> any value
     *       already on the row rather than nulling it — so seeded/prior data survives a refresh.</li>
     * </ul>
     */
    private void enrichOwnership(String cpId, CreditProposalCacheEntry entry) {
        try {
            CreditProposalDetails details = creditProposalClient.lookup(cpId);
            if (details == null) {
                return;
            }
            String rm = userId(details.rm());
            if (rm != null) {
                entry.setRmUserId(rm);
            }
            String wrm = userId(details.wrm());
            if (wrm != null) {
                entry.setWrmUserId(wrm);
            }
            String parent = details.parentCpId();
            if (parent != null && !parent.isBlank()) {
                entry.setParentCpId(parent);
            }
        } catch (RuntimeException ex) {
            LOG.warn("CP ownership lookup failed for {}: {}",
                    String.valueOf(cpId).replace('\n', '_').replace('\r', '_'),
                    String.valueOf(ex.getMessage()).replace('\n', '_').replace('\r', '_'));
        }
    }

    private static String userId(CpUser user) {
        if (user == null) {
            return null;
        }
        String id = user.userId();
        return (id == null || id.isBlank()) ? null : id;
    }

    private static CovenantAttributes toAttributes(CreditProposalCacheEntry entry) {
        Map<String, String> map;
        try {
            map = MAPPER.readValue(entry.getAttributesJson(), ATTR_TYPE);
        } catch (JsonProcessingException ex) {
            map = Map.of();
        }
        return new CovenantAttributes(entry.getCpId(), entry.getBusinessSegment(),
                entry.getSector(), map);
    }
}
