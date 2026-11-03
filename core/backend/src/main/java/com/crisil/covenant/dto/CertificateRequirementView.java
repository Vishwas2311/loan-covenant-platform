package com.crisil.covenant.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * FR-22: one certificate requirement with its derived state and the certificates received for it.
 *
 * @param state       RECEIVED when a certificate for the period is on file, OVERDUE after the due
 *                    date, otherwise PENDING
 * @param overdueDays days past the due date while not received (0 otherwise)
 */
public record CertificateRequirementView(
        String id,
        String cpId,
        LocalDate periodEnd,
        LocalDate dueDate,
        String note,
        State state,
        long overdueDays,
        List<AttachmentView> certificates) {

    public enum State { PENDING, RECEIVED, OVERDUE }
}
