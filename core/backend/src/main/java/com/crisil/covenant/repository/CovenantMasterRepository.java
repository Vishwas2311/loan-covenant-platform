package com.crisil.covenant.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import com.crisil.covenant.entity.CovenantMaster;

@Repository
public interface CovenantMasterRepository
        extends JpaRepository<CovenantMaster, String>, JpaSpecificationExecutor<CovenantMaster> {

    Optional<CovenantMaster> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    /** A CP's own custom covenants (active), surfaced in that CP's recommendation list. */
    List<CovenantMaster> findByOwnerCpIdAndActiveTrue(String ownerCpId);

    /** Per-CP custom-name uniqueness (custom names need not be globally unique). */
    boolean existsByOwnerCpIdAndNameIgnoreCase(String ownerCpId, String name);
}
