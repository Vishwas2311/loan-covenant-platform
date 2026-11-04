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
import com.crisil.covenant.enums.AssessmentResult;

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

    @EntityGraph(Assessment.WITH_COVENANT)
    Page<Assessment> findByAppliedCovenantIdOrderByScheduledAtDesc(String appliedCovenantId, Pageable pageable);

    @EntityGraph(Assessment.WITH_COVENANT_AND_COMPARISONS)
    List<Assessment> findByAppliedCovenantCpIdOrderByScheduledAtDesc(String cpId);

    @Override
    @EntityGraph(Assessment.WITH_COVENANT_AND_COMPARISONS)
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

    /**
     * Result and dates of every assessment of a batch of covenants, newest first per covenant: the
     * open-breach report's history in one query instead of one entity-graph page per covenant.
     */
    @Query("select a.appliedCovenant.id as appliedCovenantId, a.result as result, a.scheduledAt as scheduledAt, "
            + "a.executedAt as executedAt from Assessment a where a.appliedCovenant.id in :ids "
            + "order by a.appliedCovenant.id, a.scheduledAt desc")
    List<AssessmentOutcome> findOutcomesByAppliedCovenantIdIn(@Param("ids") Collection<String> ids);

    /**
     * The latest assessment (by {@code scheduledAt}, which is never null) of each covenant in a batch:
     * the portfolio summary's per-covenant lookup in one query, read through ix_assessment_applied_scheduled.
     */
    // ROW_NUMBER reads each covenant's rows once in index order (a correlated max() per row cost 100+ ms
    // for a 200-covenant batch on SQL Server). Results are stored lower case; upper() maps them to the enum.
    @Query(value = "select t.applied_covenant_id as appliedCovenantId, t.id as id, upper(t.result) as result "
            + "from (select a.applied_covenant_id, a.id, a.result, a.scheduled_at, "
            + "row_number() over (partition by a.applied_covenant_id order by a.scheduled_at desc, a.id desc) as rn "
            + "from cov_assessment a where a.applied_covenant_id in (:ids)) t where t.rn = 1", nativeQuery = true)
    List<LatestOutcome> findLatestOutcomes(@Param("ids") Collection<String> ids);

    /** A covenant's latest assessment: its id and result. */
    interface LatestOutcome {
        String getAppliedCovenantId();

        String getId();

        AssessmentResult getResult();
    }

    /** One assessment's covenant, result and dates. */
    interface AssessmentOutcome {
        String getAppliedCovenantId();

        AssessmentResult getResult();

        Instant getScheduledAt();

        Instant getExecutedAt();
    }

    /** Projection carrying an applied covenant id and the most recent {@code executedAt} for it. */
    interface AppliedCovenantLastExecuted {
        String getAppliedCovenantId();

        Instant getLastExecutedAt();
    }
}
