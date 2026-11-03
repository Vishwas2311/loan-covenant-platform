package com.crisil.covenant.dto.ops;

import com.crisil.covenant.entity.OpsAudit;
import java.time.Instant;

/**
 * One row of the append-only production-support audit trail, as returned by
 * {@code GET /api/v1/ops/audit}. {@link #outcome()} is the {@code SUCCESS}/{@code FAILURE} name.
 * Coverage-excluded ({@code dto/**}).
 *
 * @param id            the audit row id
 * @param action        the WRITE action taken (e.g. {@code reingest}, {@code cleanup})
 * @param targetType    the kind of target (e.g. {@code cp}, {@code applied_covenant})
 * @param targetId      the target id, or {@code null} for a type-wide action
 * @param performedBy   the acting operator (from {@code X-User}, else {@code system})
 * @param paramsSummary a short summary of the action's parameters, or {@code null}
 * @param outcome       {@code SUCCESS} or {@code FAILURE}
 * @param detail        a human-readable outcome detail, or {@code null}
 * @param performedAt   when the action was recorded
 */
public record OpsAuditView(
        String id,
        String action,
        String targetType,
        String targetId,
        String performedBy,
        String paramsSummary,
        String outcome,
        String detail,
        Instant performedAt) {

    /** Map a persisted {@link OpsAudit} row to its API view. */
    public static OpsAuditView from(OpsAudit audit) {
        return new OpsAuditView(
                audit.getId(), audit.getAction(), audit.getTargetType(), audit.getTargetId(),
                audit.getPerformedBy(), audit.getParamsSummary(),
                audit.getOutcome() == null ? null : audit.getOutcome().name(),
                audit.getDetail(), audit.getPerformedAt());
    }
}
