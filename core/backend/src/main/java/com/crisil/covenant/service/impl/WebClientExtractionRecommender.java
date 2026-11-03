package com.crisil.covenant.service.impl;

import com.crisil.covenant.dto.ExtractionRecommendation;
import com.crisil.covenant.service.ExtractionRecommender;
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
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Resilient C1 client ({@code POST /dps/v1/recommendations}), shaped like the ML recommender:
 * time limiter → circuit breaker → retry, and an empty list on any failure.
 */
@Component
public class WebClientExtractionRecommender implements ExtractionRecommender {

    static final String INSTANCE = "dpsRecommendations";
    private static final Logger LOG = LoggerFactory.getLogger(WebClientExtractionRecommender.class);

    private final WebClient client;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;

    public WebClientExtractionRecommender(@Qualifier("dpsWebClient") WebClient dpsWebClient,
                                          CircuitBreakerRegistry circuitBreakerRegistry,
                                          RetryRegistry retryRegistry,
                                          TimeLimiterRegistry timeLimiterRegistry) {
        this.client = dpsWebClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE);
        this.retry = retryRegistry.retry(INSTANCE);
        this.timeLimiter = timeLimiterRegistry.timeLimiter(INSTANCE);
    }

    @Override
    public List<ExtractionRecommendation> recommend(String cpId) {
        return client.post()
                .uri("/dps/v1/recommendations")
                .bodyValue(Map.of("cpId", cpId))
                .retrieve()
                .bodyToFlux(ExtractionRecommendation.class)
                .collectList()
                .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .onErrorResume(ex -> {
                    LOG.warn("Document Processing Service unavailable; onboarding without EXTRACTED rows: {}",
                            ex.toString().replace('\n', '_').replace('\r', '_'));
                    return Mono.just(List.<ExtractionRecommendation>of());
                })
                .blockOptional()
                .orElseGet(List::of);
    }
}
