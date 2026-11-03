package com.crisil.covenant.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.crisil.covenant.entity.CovenantDisposition;

@Repository
public interface CovenantDispositionRepository extends JpaRepository<CovenantDisposition, String> {

    Optional<CovenantDisposition> findByCpIdAndAppliedCovenantId(String cpId, String appliedCovenantId);

    List<CovenantDisposition> findByCpId(String cpId);

    /** Batch variant of {@link #findByCpId} for portfolio roll-ups (US-MON-20/21): avoids an N+1
     * across CPs by loading every disposition for the given CPs in one query. */
    List<CovenantDisposition> findByCpIdIn(Collection<String> cpIds);

    /** Production-support CP cleanup: delete every disposition row for a CP in one bulk statement. */
    @Modifying
    @Query("delete from CovenantDisposition d where d.cpId = :cpId")
    int deleteByCpId(@Param("cpId") String cpId);
}
