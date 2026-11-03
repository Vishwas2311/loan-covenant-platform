package com.crisil.covenant.dto;

/**
 * The bytes and metadata of a file fetched back from the external file service (US-MON-23 download).
 * Unlike {@link FileUploadResult}, a download is a read: the client may throw on failure so the caller
 * surfaces a gateway error rather than a corrupt/empty file. Coverage-excluded ({@code dto/**}).
 */
public record FileDownloadResult(
        byte[] content,
        String contentType,
        String fileName) {
}
