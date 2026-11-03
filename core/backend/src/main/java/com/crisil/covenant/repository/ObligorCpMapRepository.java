package com.crisil.covenant.repository;

import com.crisil.covenant.entity.ObligorCpMap;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the {@link ObligorCpMap} (obligor -> many CPs). Lookups run both ways: forward
 * (a CP's obligor, for execution) and reverse (an obligor's CPs, for fan-out).
 */
@Repository
public interface ObligorCpMapRepository extends JpaRepository<ObligorCpMap, String> {

    /** The mapping row for one CP, if the CP has been looked up (unique on cp_id). */
    Optional<ObligorCpMap> findByCpId(String cpId);

    /** Every CP under one obligor — the reverse fan-out direction. */
    @Query("select m.cpId from ObligorCpMap m where m.obligorId = :obligorId")
    List<String> findCpIdsByObligorId(@Param("obligorId") String obligorId);

    /**
     * The obligor a CP maps to as a bare id (no managed entity loaded). The destructive cleanup needs
     * this before deleting the mapping row, to decide whether obligor-grain staging is shared.
     */
    @Query("select m.obligorId from ObligorCpMap m where m.cpId = :cpId")
    Optional<String> findObligorIdByCpId(@Param("cpId") String cpId);

    /** Production-support CP cleanup: delete this CP's obligor-mapping row (unique on cp_id). */
    @Modifying
    @Query("delete from ObligorCpMap m where m.cpId = :cpId")
    int deleteByCpId(@Param("cpId") String cpId);
}
