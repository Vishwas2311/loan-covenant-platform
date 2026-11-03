package com.crisil.covenant.dto;

import com.crisil.covenant.entity.ComplianceCertificate;
import com.crisil.covenant.entity.CovenantAttachment;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A single uploaded file (US-MON-17): the stored external {@code fileId} plus its display metadata.
 * Used for both per-covenant evidence attachments and CP-level compliance certificates (same shape).
 * Read model only — coverage-excluded ({@code dto/**}).
 */
public record AttachmentView(
        String id,
        String fileId,
        String fileName,
        String uploadedBy,
        Instant uploadedAt,
        LocalDate periodEnd) {

    public AttachmentView(String id, String fileId, String fileName, String uploadedBy, Instant uploadedAt) {
        this(id, fileId, fileName, uploadedBy, uploadedAt, null);
    }

    public static AttachmentView from(CovenantAttachment a) {
        return new AttachmentView(a.getId(), a.getFileId(), a.getFileName(), a.getUploadedBy(),
                a.getUploadedAt());
    }

    public static AttachmentView from(ComplianceCertificate c) {
        return new AttachmentView(c.getId(), c.getFileId(), c.getFileName(), c.getUploadedBy(),
                c.getUploadedAt(), c.getPeriodEnd());
    }
}
