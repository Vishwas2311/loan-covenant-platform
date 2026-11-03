package com.crisil.covenant.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.crisil.covenant.entity.CovenantRecommendation;

@Repository
public interface CovenantRecommendationRepository extends JpaRepository<CovenantRecommendation, String> {

    List<CovenantRecommendation> findByCpId(String cpId);

    Optional<CovenantRecommendation> findByCpIdAndCovenantMasterId(String cpId, String covenantMasterId);

    /** Production-support CP cleanup: delete every persisted recommendation row for a CP. */
    @Modifying
    @Query("delete from CovenantRecommendation r where r.cpId = :cpId")
    int deleteByCpId(@Param("cpId") String cpId);
}
