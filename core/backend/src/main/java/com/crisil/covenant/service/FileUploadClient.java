package com.crisil.covenant.service;

import com.crisil.covenant.dto.FileDownloadResult;
import com.crisil.covenant.dto.FileUploadResult;

/**
 * Talks to the external file-upload service: stores a file and returns its opaque {@code fileId}
 * (US-MON-17 upload) and, for the read-only cycle viewer + live grid, fetches a stored file back
 * by id (US-MON-23 download).
 *
 * <p>The two directions have deliberately different failure contracts:
 * <ul>
 *   <li>{@link #upload} must <strong>never throw</strong> — a failed call is reported as a failed
 *       {@link FileUploadResult} so the caller shows a clear error and leaves existing data
 *       untouched (US-MON-17 AC5);</li>
 *   <li>{@link #download} <strong>may throw</strong> — a download is a read, so on failure the caller
 *       should surface a gateway error rather than serve a corrupt/empty file (US-MON-23 AC10).</li>
 * </ul>
 */
public interface FileUploadClient {

    /**
     * Forward {@code content} to the external service as multipart form data.
     *
     * @param fileName    original filename (echoed in the multipart part)
     * @param contentType MIME type; {@code null}/blank falls back to {@code application/octet-stream}
     * @param content     raw file bytes
     * @return a successful result carrying the service's {@code fileId}, or a failed result
     */
    FileUploadResult upload(String fileName, String contentType, byte[] content);

    /**
     * Fetch a previously stored file back by its {@code fileId}.
     *
     * @param fileId opaque id returned by a prior {@link #upload}
     * @return the file bytes plus its content-type and (service-supplied) filename
     * @throws org.springframework.web.server.ResponseStatusException on any non-2xx, timeout,
     *         connection error or open circuit (a download is a read and must not silently succeed)
     */
    FileDownloadResult download(String fileId);
}
