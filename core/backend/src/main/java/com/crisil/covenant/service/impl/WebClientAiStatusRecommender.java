package com.crisil.covenant.service.impl;

import com.crisil.covenant.dto.AiStatusRecommendation;
import com.crisil.covenant.dto.AiStatusRequest;
import com.crisil.covenant.dto.AiSuggestionResponse;
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
import com.crisil.covenant.service.AiStatusRecommender;

/**
 * Calls the external AI status-suggestion service over HTTP, wrapped in a resilience4j time limiter,
 * circuit breaker and retry (all bound to the {@value #INSTANCE} instance). Mirrors
 * {@code WebClientNotificationClient}: any failure — slow response, non-2xx, connection error, or an
 * open circuit — yields an empty recommendation list rather than a thrown exception, so the modal
 * shows a graceful "AI unavailable" message with no data change (US-MON-18 AC6).
 *
 * <p>Resilience is applied with the resilience4j-reactor operators (not the annotation aspects) so
 * the fallback is exercised directly by unit tests without a Spring context.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "covenant.clients.ai-suggestion.provider", havingValue = "external", matchIfMissing = true)
public class WebClientAiStatusRecommender implements AiStatusRecommender {

    /** resilience4j instance name shared by the circuit breaker, retry and time limiter. */
    static final String INSTANCE = "aiSuggestion";

    /** Fixed endpoint on the AI service; the host comes from the injected WebClient. */
    static final String PATH = "/api/v1/ai/covenant-status";

    private static final Logger LOG = LoggerFactory.getLogger(WebClientAiStatusRecommender.class);

    private final WebClient client;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;

    public WebClientAiStatusRecommender(WebClient aiSuggestionWebClient,
                                        CircuitBreakerRegistry circuitBreakerRegistry,
                                        RetryRegistry retryRegistry,
                                        TimeLimiterRegistry timeLimiterRegistry) {
        this.client = aiSuggestionWebClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE);
        this.retry = retryRegistry.retry(INSTANCE);
        this.timeLimiter = timeLimiterRegistry.timeLimiter(INSTANCE);
    }

    @Override
    public List<AiStatusRecommendation> recommend(AiStatusRequest request) {
        return client.post()
                .uri(PATH)
                .bodyValue(request)
                .retrieve()
                .toEntity(AiSuggestionResponse.class)
                .map(resp -> {
                    AiSuggestionResponse body = resp.getBody();
                    return body == null || body.recommendations() == null
                            ? List.<AiStatusRecommendation>of()
                            : body.recommendations();
                })
                // innermost → outermost: time limiter, then circuit breaker, then retry.
                .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .onErrorResume(ex -> Mono.just(fallback(ex)))
                .blockOptional()
                .orElseGet(List::of);
    }

    private List<AiStatusRecommendation> fallback(Throwable ex) {
        // Sanitise CR/LF out of the exception text before logging (CRLF log injection).
        LOG.warn("AI status-suggestion call failed; returning no suggestions: {}",
                ex.toString().replace('\n', '_').replace('\r', '_'));
        return List.of();
    }
}
