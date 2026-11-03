package com.crisil.covenant.service.impl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.dto.AiStatusRequest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class DpsAiStatusRecommenderTest {

    private WireMockServer wireMock;
    private DpsAiStatusRecommender recommender;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        WebClient client = WebClient.builder().baseUrl("http://127.0.0.1:" + wireMock.port()).build();
        RetryRegistry rr = RetryRegistry.of(RetryConfig.custom().maxAttempts(1).waitDuration(Duration.ofMillis(10))
                .build());
        recommender = new DpsAiStatusRecommender(client, CircuitBreakerRegistry.ofDefaults(), rr,
                TimeLimiterRegistry.of(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(8)).build()));
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    private static AiStatusRequest request() {
        return new AiStatusRequest("CP-1", List.of(), List.of());
    }

    @Test
    void readsTheBareArray() {
        wireMock.stubFor(post(urlEqualTo("/dps/v1/ai-suggestions")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("[{\"appliedCovenantId\":\"a1\",\"recommendedStatus\":\"WAIVER\","
                        + "\"rationale\":\"small\",\"confidence\":70}]")));
        assertThat(recommender.recommend(request())).hasSize(1);
    }

    @Test
    void emptyOnFailure() {
        wireMock.stubFor(post(urlEqualTo("/dps/v1/ai-suggestions")).willReturn(aResponse().withStatus(500)));
        assertThat(recommender.recommend(request())).isEmpty();
    }
}
