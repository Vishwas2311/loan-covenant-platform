package com.crisil.covenant.service;

import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.dto.ops.OpsAuditView;
import com.crisil.covenant.entity.OpsAudit;
import com.crisil.covenant.enums.OpsAuditOutcome;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.repository.OpsAuditRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the append-only production-support audit trail ({@code cov_ops_audit}). Only WRITE actions
 * (re-ingest, clear-ingestion-state, re-assess, destructive cleanup) are audited; read-only
 * diagnostics are not.
 *
 * <p>Each row is persisted in its <strong>own {@code REQUIRES_NEW} transaction</strong> so the record
 * — success or failure — survives even when the caller's transaction rolls back. That is essential for
 * destructive operations: if the cleanup transaction aborts mid-way, the {@link OpsAuditOutcome#FAILURE}
 * row still commits, leaving an accurate trail of what was attempted.
 *
 * <p>The acting operator is resolved from the {@code X-User} header (falling back to {@code "system"}),
 * and all free-text is CR/LF-sanitised and length-clamped to the column widths so untrusted input can
 * neither forge log lines nor overflow the column.
 */
@Service
public class OpsAuditService {

    private static final String SYSTEM_USER = "system";
    private static final int PERFORMED_BY_MAX = 50;
    private static final int PARAMS_MAX = 2000;
    private static final int DETAIL_MAX = 2000;

    private final OpsAuditRepository repository;

    public OpsAuditService(OpsAuditRepository repository) {
        this.repository = repository;
    }

    /**
     * Read the audit trail for a target, newest first. {@code targetType} is required — the trail is
     * indexed by type (or by type + id), so a type-less scan is deliberately not offered; {@code
     * targetId} is optional, and when blank the whole trail for the type is returned. Read-only.
     *
     * @throws ApiException 400 {@code OPS_AUDIT_TARGET_TYPE_REQUIRED} when {@code targetType} is blank
     */
    @Transactional(readOnly = true)
    public List<OpsAuditView> trail(String targetType, String targetId) {
        if (targetType == null || targetType.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OPS_AUDIT_TARGET_TYPE_REQUIRED",
                    "Query parameter 'targetType' is required");
        }
        List<OpsAudit> rows = (targetId == null || targetId.isBlank())
                ? repository.findByTargetTypeOrderByPerformedAtDesc(targetType)
                : repository.findByTargetTypeAndTargetIdOrderByPerformedAtDesc(targetType, targetId);
        return rows.stream().map(OpsAuditView::from).toList();
    }

    /** Record a successful WRITE action. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OpsAudit recordSuccess(String action, String targetType, String targetId,
                                  String paramsSummary, String detail) {
        return save(action, targetType, targetId, paramsSummary, OpsAuditOutcome.SUCCESS, detail);
    }

    /** Record a failed / rejected WRITE action; commits independently of the caller's rollback. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OpsAudit recordFailure(String action, String targetType, String targetId,
                                  String paramsSummary, String detail) {
        return save(action, targetType, targetId, paramsSummary, OpsAuditOutcome.FAILURE, detail);
    }

    private OpsAudit save(String action, String targetType, String targetId, String paramsSummary,
                          OpsAuditOutcome outcome, String detail) {
        OpsAudit audit = new OpsAudit();
        audit.setAction(clamp(sanitise(action), PARAMS_MAX));
        audit.setTargetType(sanitise(targetType));
        audit.setTargetId(clamp(sanitise(targetId), 128));
        audit.setPerformedBy(clamp(resolveUser(), PERFORMED_BY_MAX));
        audit.setParamsSummary(clamp(sanitise(paramsSummary), PARAMS_MAX));
        audit.setOutcome(outcome);
        audit.setDetail(clamp(sanitise(detail), DETAIL_MAX));
        audit.setPerformedAt(Instant.now());
        return repository.save(audit);
    }

    private static String resolveUser() {
        // CurrentUserContext already rejects blank X-User headers, so a present value is non-blank.
        return CurrentUserContext.currentUser()
                .map(OpsAuditService::sanitise)
                .orElse(SYSTEM_USER);
    }

    private static String sanitise(String value) {
        if (value == null) {
            return null;
        }
        return value.replace('\n', '_').replace('\r', '_');
    }

    private static String clamp(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
