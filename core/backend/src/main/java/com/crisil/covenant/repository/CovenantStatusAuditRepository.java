package com.crisil.covenant.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.crisil.covenant.entity.CovenantStatusAudit;

@Repository
public interface CovenantStatusAuditRepository extends JpaRepository<CovenantStatusAudit, String> {

    List<CovenantStatusAudit> findByCpIdOrderByChangedDateDesc(String cpId);

    List<CovenantStatusAudit> findByAppliedCovenantIdOrderByChangedDateDesc(String appliedCovenantId);

    /** Production-support CP cleanup: delete every disposition-change audit row for a CP. */
    @Modifying
    @Query("delete from CovenantStatusAudit a where a.cpId = :cpId")
    int deleteByCpId(@Param("cpId") String cpId);
}
