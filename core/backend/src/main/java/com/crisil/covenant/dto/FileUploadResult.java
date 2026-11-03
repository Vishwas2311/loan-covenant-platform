package com.crisil.covenant.dto;

/**
 * Internal outcome of one file-upload-service call — never sent over the wire. A 2xx response that
 * carries a {@code fileId} is a {@code success}; any non-2xx, timeout, connection error or open
 * circuit yields a failed result (the client never throws) so the caller can surface a clear error
 * without corrupting existing data (US-MON-17 AC5). Coverage-excluded ({@code dto/**}).
 */
public record FileUploadResult(
        boolean success,
        String fileId,
        String fileName,
        Integer httpStatus,
        String error) {
}
