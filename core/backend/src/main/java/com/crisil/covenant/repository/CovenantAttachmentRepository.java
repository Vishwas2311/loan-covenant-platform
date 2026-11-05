package com.crisil.covenant.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.crisil.covenant.entity.CovenantAttachment;

/**
 * Evidence attachments for covenant rows (US-MON-17). Read for the grid (per CP, batched) and per
 * covenant; scoped lookups guard deletes so a caller can only remove a file on its own CP+covenant.
 */
@Repository
public interface CovenantAttachmentRepository extends JpaRepository<CovenantAttachment, String> {

    List<CovenantAttachment> findByCpIdOrderByUploadedAtDesc(String cpId);

    /** Attachments of many CPs at once (callers keep the list under 2,100 ids), newest first. */
    List<CovenantAttachment> findByCpIdInOrderByUploadedAtDesc(Collection<String> cpIds);

    List<CovenantAttachment> findByAppliedCovenantIdOrderByUploadedAtDesc(String appliedCovenantId);

    Optional<CovenantAttachment> findByIdAndCpIdAndAppliedCovenantId(String id, String cpId,
                                                                     String appliedCovenantId);

    /** Production-support CP cleanup: delete every evidence-attachment row for a CP. */
    @Modifying
    @Query("delete from CovenantAttachment a where a.cpId = :cpId")
    int deleteByCpId(@Param("cpId") String cpId);
}
