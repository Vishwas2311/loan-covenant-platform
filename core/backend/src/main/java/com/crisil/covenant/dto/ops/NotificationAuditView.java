package com.crisil.covenant.dto.ops;

import com.crisil.covenant.entity.NotificationAudit;
import java.time.Instant;

/**
 * One breach-summary e-mail Core handed to the notification service: who it went to, which CPs it
 * covered, the request it sent (the e-mail's content) and whether the service accepted it.
 */
public record NotificationAuditView(
        String id,
        String runId,
        String emailTo,
        String emailCc,
        String cpIds,
        String requestBody,
        Integer httpStatus,
        String outcome,
        String errorMessage,
        String changedBy,
        Instant changedDate) {

    public static NotificationAuditView from(NotificationAudit audit) {
        return new NotificationAuditView(
                audit.getId(),
                audit.getRunId(),
                audit.getEmailTo(),
                audit.getEmailCc(),
                audit.getCpIds(),
                audit.getRequestBody(),
                audit.getHttpStatus(),
                audit.getOutcome() == null ? null : audit.getOutcome().name(),
                audit.getErrorMessage(),
                audit.getChangedBy(),
                audit.getChangedDate());
    }
}
