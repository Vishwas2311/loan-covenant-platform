package com.crisil.covenant.service.impl;

import com.crisil.covenant.dto.AiStatusRecommendation;
import com.crisil.covenant.dto.AiStatusRequest;
import com.crisil.covenant.service.AiStatusRecommender;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * C3 through the Document Processing Service ({@code POST /dps/v1/ai-suggestions}), selected with
 * {@code covenant.clients.ai-suggestion.provider=dps}. Same resilience and empty-list fallback as the
 * external AI client; DPS answers with a bare array instead of a wrapper object.
 */
@Component
@ConditionalOnProperty(name = "covenant.clients.ai-suggestion.provider", havingValue = "dps")
public class DpsAiStatusRecommender implements AiStatusRecommender {

    static final String PATH = "/dps/v1/ai-suggestions";
    private static final Logger LOG = LoggerFactory.getLogger(DpsAiStatusRecommender.class);

    private final WebClient client;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;

    public DpsAiStatusRecommender(@Qualifier("dpsWebClient") WebClient dpsWebClient,
                                  CircuitBreakerRegistry circuitBreakerRegistry,
                                  RetryRegistry retryRegistry,
                                  TimeLimiterRegistry timeLimiterRegistry) {
        this.client = dpsWebClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(WebClientAiStatusRecommender.INSTANCE);
        this.retry = retryRegistry.retry(WebClientAiStatusRecommender.INSTANCE);
        this.timeLimiter = timeLimiterRegistry.timeLimiter(WebClientAiStatusRecommender.INSTANCE);
    }

    @Override
    public List<AiStatusRecommendation> recommend(AiStatusRequest request) {
        return client.post()
                .uri(PATH)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(AiStatusRecommendation.class)
                .collectList()
                .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .onErrorResume(ex -> {
                    LOG.warn("DPS AI suggestions unavailable: {}",
                            ex.toString().replace('\n', '_').replace('\r', '_'));
                    return Mono.just(List.<AiStatusRecommendation>of());
                })
                .blockOptional()
                .orElseGet(List::of);
    }
}
