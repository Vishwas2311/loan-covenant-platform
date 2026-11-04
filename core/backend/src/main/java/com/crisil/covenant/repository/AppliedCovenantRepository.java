package com.crisil.covenant.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.enums.AppliedCovenantStatus;

@Repository
public interface AppliedCovenantRepository extends JpaRepository<AppliedCovenant, String> {

    @EntityGraph(AppliedCovenant.WITH_MASTER)
    List<AppliedCovenant> findByCpIdOrderByIdAsc(String cpId);

    /**
     * Portfolio-scoped variant of {@link #findByCpIdOrderByIdAsc(String)}: the applied covenants across a
     * set of CPs (the caller's owned portfolio), eager-loading the master. An empty collection yields an
     * empty list, so the monitoring reads can fail closed without a special case.
     */
    @EntityGraph(AppliedCovenant.WITH_MASTER)
    List<AppliedCovenant> findByCpIdInOrderByIdAsc(Collection<String> cpIds);

    /**
     * Page through covenants in one status, eager-loading the master so the daily breach sweep can
     * process a large book in bounded-memory batches instead of loading everything with {@code findAll}.
     */
    @EntityGraph(AppliedCovenant.WITH_MASTER)
    Page<AppliedCovenant> findByStatus(AppliedCovenantStatus status, Pageable pageable);

    @Override
    @EntityGraph(AppliedCovenant.WITH_MASTER)
    Optional<AppliedCovenant> findById(String id);

    boolean existsByCpIdAndCovenantMasterId(String cpId, String covenantMasterId);

    boolean existsByIdAndCpId(String id, String cpId);

    long countByCovenantMasterId(String covenantMasterId);

    long countByCovenantMasterIdAndStatus(String covenantMasterId, AppliedCovenantStatus status);

    List<AppliedCovenant> findByCovenantMasterIdAndStatusOrderByIdAsc(String covenantMasterId,
                                                                    AppliedCovenantStatus status);

    @org.springframework.data.jpa.repository.Query(
            "select a.cpId from AppliedCovenant a where a.covenantMaster.id = :covenantMasterId order by a.cpId")
    java.util.List<String> findCpIdsByCovenantMasterId(@org.springframework.data.repository.query.Param("covenantMasterId") String covenantMasterId);

    /**
     * The applied-covenant ids for a CP as a lightweight id projection (no entity graph loaded). Used by
     * the destructive cleanup to drive the FK-child bulk deletes without polluting the persistence
     * context with managed entities that are about to be removed.
     */
    @Query("select a.id from AppliedCovenant a where a.cpId = :cpId")
    List<String> findIdsByCpId(@Param("cpId") String cpId);

    /**
     * Production-support CP cleanup: delete the applied-covenant rows for a CP in one bulk statement.
     * Callers MUST first clear the FK children (assessments + their thresholds, monitoring schedules,
     * applied-covenant audits) or the database will reject the delete.
     */
    @Modifying
    @Query("delete from AppliedCovenant a where a.cpId = :cpId")
    int deleteByCpId(@Param("cpId") String cpId);
}
