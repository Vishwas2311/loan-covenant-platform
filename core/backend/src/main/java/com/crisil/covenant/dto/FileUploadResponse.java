package com.crisil.covenant.dto;

/**
 * Wire response from the external file-upload service: the stored {@code fileId} (opaque handle we
 * persist and later hand back to downstream services) and the service's echoed {@code fileName}.
 * Deserialised verbatim from the service contract. Coverage-excluded ({@code dto/**}).
 */
public record FileUploadResponse(String fileId, String fileName) {
}
