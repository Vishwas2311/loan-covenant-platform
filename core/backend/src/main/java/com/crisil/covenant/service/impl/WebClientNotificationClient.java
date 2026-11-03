package com.crisil.covenant.service.impl;

import com.crisil.covenant.dto.NotificationRequest;
import com.crisil.covenant.dto.NotificationResult;
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
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import com.crisil.covenant.service.NotificationClient;

/**
 * Calls the external notification service over HTTP, wrapped in a resilience4j time limiter, circuit
 * breaker and retry (all bound to the {@value #INSTANCE} instance). Mirrors
 * {@code WebClientMlThresholdRecommender}: any failure — slow response, non-2xx, connection error, or
 * an open circuit — is turned into a failed {@link NotificationResult} rather than thrown, so a breach
 * run records the outcome and continues (US-MON-14 AC6).
 *
 * <p>Resilience is applied with the resilience4j-reactor operators (not the annotation aspects) so the
 * fallback is exercised directly by unit tests without a Spring context.
 */
@Component
public class WebClientNotificationClient implements NotificationClient {

    /** resilience4j instance name shared by the circuit breaker, retry and time limiter. */
    static final String INSTANCE = "notifications";

    /** Fixed endpoint on the notification service; the host comes from the injected WebClient. */
    static final String PATH = "/api/v1/notifications";

    private static final Logger LOG = LoggerFactory.getLogger(WebClientNotificationClient.class);

    private final WebClient client;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;

    public WebClientNotificationClient(WebClient notificationWebClient,
                                       CircuitBreakerRegistry circuitBreakerRegistry,
                                       RetryRegistry retryRegistry,
                                       TimeLimiterRegistry timeLimiterRegistry) {
        this.client = notificationWebClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE);
        this.retry = retryRegistry.retry(INSTANCE);
        this.timeLimiter = timeLimiterRegistry.timeLimiter(INSTANCE);
    }

    @Override
    public NotificationResult send(NotificationRequest request) {
        return client.post()
                .uri(PATH)
                .bodyValue(request)
                .retrieve()
                .toEntity(String.class)
                .map(resp -> new NotificationResult(
                        resp.getStatusCode().is2xxSuccessful(),
                        resp.getStatusCode().value(),
                        resp.getBody(),
                        null))
                // innermost → outermost: time limiter, then circuit breaker, then retry.
                .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .onErrorResume(ex -> Mono.just(toFailure(ex)))
                .blockOptional()
                .orElseGet(() -> new NotificationResult(false, null, null, "no response"));
    }

    private NotificationResult toFailure(Throwable ex) {
        // Sanitise CR/LF out of the exception text before logging (CRLF log injection).
        LOG.warn("Notification service call failed; recording failure: {}",
                ex.toString().replace('\n', '_').replace('\r', '_'));
        Integer status = null;
        String body = null;
        if (ex instanceof WebClientResponseException wcre) {
            status = wcre.getStatusCode().value();
            body = wcre.getResponseBodyAsString();
        }
        String err = ex.getMessage() == null
                ? ex.getClass().getSimpleName()
                : ex.getMessage().replace('\n', '_').replace('\r', '_');
        return new NotificationResult(false, status, body, err);
    }
}
