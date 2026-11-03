package com.crisil.covenant.repository;

import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.crisil.covenant.entity.MonitoringSchedule;

@Repository
public interface MonitoringScheduleRepository extends JpaRepository<MonitoringSchedule, String> {

    Optional<MonitoringSchedule> findByAppliedCovenantId(String appliedCovenantId);

    /**
     * Production-support CP cleanup: delete the Quartz-binding schedule row(s) for the given applied
     * covenants in one bulk statement (filters on the {@code applied_covenant_id} FK column directly).
     */
    @Modifying
    @Query("delete from MonitoringSchedule s where s.appliedCovenant.id in :appliedCovenantIds")
    int deleteByAppliedCovenantIdIn(@Param("appliedCovenantIds") Collection<String> appliedCovenantIds);
}
