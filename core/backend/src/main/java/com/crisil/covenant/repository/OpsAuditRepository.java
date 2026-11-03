package com.crisil.covenant.repository;

import com.crisil.covenant.entity.OpsAudit;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the production-support audit trail. Rows are append-only; the toolkit only ever
 * inserts (via {@code OpsAuditService}) and reads back the most-recent-first trail for a target.
 */
@Repository
public interface OpsAuditRepository extends JpaRepository<OpsAudit, String> {

    /** The trail for one target, newest first. */
    List<OpsAudit> findByTargetTypeAndTargetIdOrderByPerformedAtDesc(String targetType, String targetId);

    /** The whole trail for a target type (any id), newest first. */
    List<OpsAudit> findByTargetTypeOrderByPerformedAtDesc(String targetType);
}
