package com.crisil.covenant.repository;

import java.time.Instant;
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
import com.crisil.covenant.entity.Assessment;

@Repository
public interface AssessmentRepository extends JpaRepository<Assessment, String> {

    /**
     * Production-support CP cleanup (child rows first): delete every decomposed comparison belonging to
     * an assessment of one of the given applied covenants, via a subquery on the assessment's foreign
     * key so no join is needed in the bulk statement. Bulk deletes bypass the JPA cascade, so these must
     * be removed before {@link #deleteByAppliedCovenantIdIn} clears the parent assessments.
     */
    @Modifying
    @Query("delete from AssessmentThreshold t where t.assessment.id in "
            + "(select a.id from Assessment a where a.appliedCovenant.id in :appliedCovenantIds)")
    int deleteThresholdsByAppliedCovenantIdIn(
            @Param("appliedCovenantIds") Collection<String> appliedCovenantIds);

    /**
     * Production-support CP cleanup: delete every assessment of the given applied covenants in one bulk
     * statement (their {@code cov_assessment_threshold} children must already be gone). Filters on the
     * {@code applied_covenant_id} foreign-key column directly, so no join is emitted.
     */
    @Modifying
    @Query("delete from Assessment a where a.appliedCovenant.id in :appliedCovenantIds")
    int deleteByAppliedCovenantIdIn(@Param("appliedCovenantIds") Collection<String> appliedCovenantIds);

    @EntityGraph(attributePaths = {"appliedCovenant", "appliedCovenant.covenantMaster"})
    Page<Assessment> findByAppliedCovenantIdOrderByScheduledAtDesc(String appliedCovenantId, Pageable pageable);

    @EntityGraph(attributePaths = {"appliedCovenant", "appliedCovenant.covenantMaster", "comparisons"})
    List<Assessment> findByAppliedCovenantCpIdOrderByScheduledAtDesc(String cpId);

    @Override
    @EntityGraph(attributePaths = {"appliedCovenant", "appliedCovenant.covenantMaster", "comparisons"})
    Optional<Assessment> findById(String id);

    /**
     * The last {@code executedAt} per applied covenant, for a batch of covenant ids in one grouped
     * query — replaces the daily breach run's N+1 "latest assessment per covenant" lookup. Covenants
     * whose assessments have never executed (only pending rows) yield a {@code null} max and are
     * treated by the caller as never assessed.
     */
    @Query("select a.appliedCovenant.id as appliedCovenantId, max(a.executedAt) as lastExecutedAt "
            + "from Assessment a where a.appliedCovenant.id in :ids group by a.appliedCovenant.id")
    List<AppliedCovenantLastExecuted> findLatestExecutedAtByAppliedCovenantIdIn(
            @Param("ids") Collection<String> ids);

    /** Projection carrying an applied covenant id and the most recent {@code executedAt} for it. */
    interface AppliedCovenantLastExecuted {
        String getAppliedCovenantId();

        Instant getLastExecutedAt();
    }
}
