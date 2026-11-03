package com.crisil.covenant.service.impl;

import com.crisil.covenant.dto.FileDownloadResult;
import com.crisil.covenant.dto.FileUploadResponse;
import com.crisil.covenant.dto.FileUploadResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import com.crisil.covenant.service.FileUploadClient;

/**
 * Uploads files to the external file-upload service over HTTP, wrapped in a resilience4j time
 * limiter, circuit breaker and retry (all bound to the {@value #INSTANCE} instance). Mirrors
 * {@code WebClientNotificationClient}: any failure — slow response, non-2xx, connection error, or an
 * open circuit — is turned into a failed {@link FileUploadResult} rather than thrown, so the caller
 * surfaces a clear error and leaves existing data untouched (US-MON-17 AC5).
 *
 * <p>Resilience is applied with the resilience4j-reactor operators (not the annotation aspects) so
 * the fallback is exercised directly by unit tests without a Spring context.
 */
@Component
public class WebClientFileUploadClient implements FileUploadClient {

    /** resilience4j instance name shared by the circuit breaker, retry and time limiter. */
    static final String INSTANCE = "fileUpload";

    /** Fixed endpoint on the file-upload service; the host comes from the injected WebClient. */
    static final String PATH = "/api/v1/files";

    /** Multipart part name carrying the file bytes. */
    static final String PART = "file";

    private static final Logger LOG = LoggerFactory.getLogger(WebClientFileUploadClient.class);

    private final WebClient client;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;

    public WebClientFileUploadClient(WebClient fileUploadWebClient,
                                     CircuitBreakerRegistry circuitBreakerRegistry,
                                     RetryRegistry retryRegistry,
                                     TimeLimiterRegistry timeLimiterRegistry) {
        this.client = fileUploadWebClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE);
        this.retry = retryRegistry.retry(INSTANCE);
        this.timeLimiter = timeLimiterRegistry.timeLimiter(INSTANCE);
    }

    @Override
    public FileUploadResult upload(String fileName, String contentType, byte[] content) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part(PART, new ByteArrayResource(content == null ? new byte[0] : content))
                .contentType(resolveType(contentType))
                .filename(fileName);
        return client.post()
                .uri(PATH)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .retrieve()
                .toEntity(FileUploadResponse.class)
                .map(resp -> toResult(resp, fileName))
                // innermost → outermost: time limiter, then circuit breaker, then retry.
                .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .onErrorResume(ex -> Mono.just(toFailure(ex, fileName)))
                .blockOptional()
                .orElseGet(() -> new FileUploadResult(false, null, fileName, null, "no response"));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Unlike {@link #upload}, a download is a read: any failure — non-2xx, timeout, connection
     * error or open circuit — is turned into a {@code 502 BAD_GATEWAY} {@link ResponseStatusException}
     * (after the same time-limiter/circuit-breaker/retry operators) rather than a silent empty result,
     * so the caller never serves a corrupt/empty file (US-MON-23 AC10).
     */
    @Override
    public FileDownloadResult download(String fileId) {
        return client.get()
                .uri(PATH + "/{fileId}", fileId)
                .retrieve()
                .toEntity(byte[].class)
                .map(resp -> toDownload(resp, fileId))
                // innermost → outermost: time limiter, then circuit breaker, then retry.
                .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .onErrorMap(ex -> !(ex instanceof ResponseStatusException), ex -> toDownloadFailure(ex, fileId))
                .blockOptional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "The file-upload service could not return the requested file"));
    }

    private static FileDownloadResult toDownload(ResponseEntity<byte[]> resp, String fileId) {
        byte[] body = resp.getBody();
        MediaType type = resp.getHeaders().getContentType();
        String contentType = type == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : type.toString();
        String fileName = resp.getHeaders().getContentDisposition().getFilename();
        return new FileDownloadResult(body == null ? new byte[0] : body, contentType,
                fileName == null || fileName.isBlank() ? fileId : fileName);
    }

    private ResponseStatusException toDownloadFailure(Throwable ex, String fileId) {
        // Sanitise CR/LF out of the id + exception text before logging (CRLF log injection).
        LOG.warn("File-download service call failed for {}: {}",
                String.valueOf(fileId).replace('\n', '_').replace('\r', '_'),
                ex.toString().replace('\n', '_').replace('\r', '_'));
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "The file-upload service could not return the requested file");
    }

    private static FileUploadResult toResult(ResponseEntity<FileUploadResponse> resp, String fileName) {
        FileUploadResponse resBody = resp.getBody();
        String fileId = resBody == null ? null : resBody.fileId();
        String name = resBody != null && resBody.fileName() != null ? resBody.fileName() : fileName;
        boolean success = resp.getStatusCode().is2xxSuccessful() && fileId != null;
        return new FileUploadResult(success, fileId, name, resp.getStatusCode().value(), null);
    }

    private FileUploadResult toFailure(Throwable ex, String fileName) {
        // Sanitise CR/LF out of the exception text before logging (CRLF log injection).
        LOG.warn("File-upload service call failed; recording failure: {}",
                ex.toString().replace('\n', '_').replace('\r', '_'));
        Integer status = null;
        if (ex instanceof WebClientResponseException wcre) {
            status = wcre.getStatusCode().value();
        }
        String err = ex.getMessage() == null
                ? ex.getClass().getSimpleName()
                : ex.getMessage().replace('\n', '_').replace('\r', '_');
        return new FileUploadResult(false, null, fileName, status, err);
    }

    private static MediaType resolveType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (RuntimeException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
