package com.crisil.covenant.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.crisil.covenant.entity.AppliedCovenantAudit;

@Repository
public interface AppliedCovenantAuditRepository extends JpaRepository<AppliedCovenantAudit, String> {

    List<AppliedCovenantAudit> findByAppliedCovenantIdOrderByChangedDateDesc(String appliedCovenantId);

    /**
     * Production-support CP cleanup: delete the frequency/cron/status change-audit row(s) for the given
     * applied covenants in one bulk statement.
     */
    @Modifying
    @Query("delete from AppliedCovenantAudit a where a.appliedCovenantId in :appliedCovenantIds")
    int deleteByAppliedCovenantIdIn(@Param("appliedCovenantIds") Collection<String> appliedCovenantIds);
}
