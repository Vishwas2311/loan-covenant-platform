package com.crisil.covenant.service;

import com.crisil.covenant.entity.ObligorCpMap;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import com.crisil.covenant.repository.ObligorCpMapRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Captures and resolves the (obligor -> many CPs) relationship. A CP's obligor is only revealed by the
 * upstream at lookup time, so the mapping is recorded there (see {@code RecommendationController}).
 * {@code cov_obligor_cp_map} is the source of truth; the obligor is also denormalized onto the CP
 * cache so the hot execution path can resolve cp -> obligor without a join.
 */
@Service
@Transactional
public class ObligorMappingService {

    private final ObligorCpMapRepository mappingRepo;
    private final CreditProposalCacheRepository cacheRepo;

    public ObligorMappingService(ObligorCpMapRepository mappingRepo,
                                 CreditProposalCacheRepository cacheRepo) {
        this.mappingRepo = mappingRepo;
        this.cacheRepo = cacheRepo;
    }

    /**
     * Idempotently record that {@code cpId} belongs to {@code obligorId}. No-op when either id is
     * blank — we never fabricate an obligor. Also denormalizes the obligor onto the CP cache row when
     * one already exists, keeping the forward cp -> obligor copy current for the daily run.
     */
    public void recordMapping(String cpId, String obligorId) {
        if (isBlank(cpId) || isBlank(obligorId)) {
            return;
        }
        ObligorCpMap mapping = mappingRepo.findByCpId(cpId).orElseGet(ObligorCpMap::new);
        mapping.setCpId(cpId);
        mapping.setObligorId(obligorId);
        mappingRepo.save(mapping);

        cacheRepo.findById(cpId).ifPresent(entry -> {
            entry.setObligorId(obligorId);
            cacheRepo.save(entry);
        });
    }

    /** The obligor a CP belongs to, if the CP has been looked up. */
    @Transactional(readOnly = true)
    public Optional<String> obligorFor(String cpId) {
        if (isBlank(cpId)) {
            return Optional.empty();
        }
        return mappingRepo.findByCpId(cpId).map(ObligorCpMap::getObligorId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
