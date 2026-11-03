package com.crisil.covenant.service.impl;

import com.crisil.covenant.dto.MlThresholdRecommendation;
import com.crisil.covenant.dto.MlThresholdRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import com.crisil.covenant.service.MlThresholdRecommender;

/**
 * Calls the external ML threshold service over HTTP, wrapped in a resilience4j time limiter, circuit
 * breaker and retry (all bound to the {@value #INSTANCE} instance). Any failure — slow response,
 * 5xx, connection error, or an open circuit — is swallowed and turned into an empty list so the
 * onboarding flow degrades gracefully (US-ML-12 AC6).
 *
 * <p>The resilience is applied with the resilience4j-reactor operators (not the annotation aspects)
 * so the fallback is exercised directly by unit tests without a Spring context.
 */
@Component
public class WebClientMlThresholdRecommender implements MlThresholdRecommender {

    /** resilience4j instance name shared by the circuit breaker, retry and time limiter. */
    static final String INSTANCE = "mlThresholds";

    private static final Logger LOG = LoggerFactory.getLogger(WebClientMlThresholdRecommender.class);

    private final WebClient client;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;

    public WebClientMlThresholdRecommender(WebClient mlWebClient,
                                           CircuitBreakerRegistry circuitBreakerRegistry,
                                           RetryRegistry retryRegistry,
                                           TimeLimiterRegistry timeLimiterRegistry) {
        this.client = mlWebClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE);
        this.retry = retryRegistry.retry(INSTANCE);
        this.timeLimiter = timeLimiterRegistry.timeLimiter(INSTANCE);
    }

    @Override
    public List<MlThresholdRecommendation> recommend(MlThresholdRequest request) {
        return client.post()
                .uri("/threshold-recommendations")
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(MlThresholdRecommendation.class)
                .collectList()
                // innermost → outermost: time limiter, then circuit breaker, then retry.
                .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .onErrorResume(ex -> {
                    // Sanitise CR/LF out of the exception text before logging (CRLF log injection).
                    LOG.warn("ML threshold service unavailable; proceeding with no recommendations: {}",
                            ex.toString().replace('\n', '_').replace('\r', '_'));
                    return Mono.just(List.<MlThresholdRecommendation>of());
                })
                .blockOptional()
                .orElseGet(List::of);
    }
}
