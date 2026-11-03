package com.crisil.covenant.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.crisil.covenant.entity.ThresholdAudit;

@Repository
public interface ThresholdAuditRepository extends JpaRepository<ThresholdAudit, String> {

    List<ThresholdAudit> findByCpIdOrderByChangedDateDesc(String cpId);

    List<ThresholdAudit> findByAppliedCovenantIdOrderByChangedDateDesc(String appliedCovenantId);

    /** Production-support CP cleanup: delete every ML/threshold-provenance audit row for a CP. */
    @Modifying
    @Query("delete from ThresholdAudit t where t.cpId = :cpId")
    int deleteByCpId(@Param("cpId") String cpId);
}
