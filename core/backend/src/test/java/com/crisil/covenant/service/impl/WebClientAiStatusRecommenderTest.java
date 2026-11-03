package com.crisil.covenant.service.impl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.dto.AiStatusRecommendation;
import com.crisil.covenant.dto.AiStatusRequest;
import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

/**
 * US-MON-18: the resilient AI recommender returns per-covenant suggestions on 2xx and an empty list
 * on any failure (never throws), so the modal shows a graceful "AI unavailable" message (AC6).
 */
class WebClientAiStatusRecommenderTest {

    private WireMockServer wireMock;
    private LoopResources loopResources;
    private WebClientAiStatusRecommender recommender;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        loopResources = LoopResources.create("ai-test", 1, true);
        HttpClient httpClient = HttpClient.create(ConnectionProvider.newConnection())
                .runOn(loopResources)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                .responseTimeout(Duration.ofSeconds(2));
        WebClient web = WebClient.builder()
                .baseUrl("http://127.0.0.1:" + wireMock.port())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        TimeLimiterRegistry tlr = TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(8))
                .cancelRunningFuture(true)
                .build());
        RetryRegistry rr = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(20))
                .build());
        CircuitBreakerRegistry cbr = CircuitBreakerRegistry.ofDefaults();
        recommender = new WebClientAiStatusRecommender(web, cbr, rr, tlr);
    }

    @AfterEach
    void stop() {
        wireMock.stop();
        loopResources.disposeLater().block(Duration.ofSeconds(5));
    }

    private List<AiStatusRecommendation> recommend() {
        return recommender.recommend(new AiStatusRequest("CP-1", List.of(), List.of()));
    }

    @Test
    void returnsRecommendationsOn2xx() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/ai/covenant-status"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"recommendations\":[{\"appliedCovenantId\":1,"
                                + "\"recommendedStatus\":\"WAIVER\",\"rationale\":\"cured\","
                                + "\"confidence\":0.8}]}")));

        List<AiStatusRecommendation> out = recommend();

        assertThat(out).hasSize(1);
        assertThat(out.get(0).appliedCovenantId()).isEqualTo("1");
        assertThat(out.get(0).recommendedStatus()).isEqualTo(CovenantDispositionStatus.WAIVER);
        assertThat(out.get(0).confidence()).isEqualTo(0.8);
    }

    @Test
    void emptyBodyYieldsEmptyList() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/ai/covenant-status"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        assertThat(recommend()).isEmpty();
    }

    @Test
    void failureOn5xxYieldsEmptyListWithoutThrowing() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/ai/covenant-status"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        assertThat(recommend()).isEmpty();
    }

    @Test
    void timeoutYieldsEmptyList() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/ai/covenant-status"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(4000)
                        .withBody("{\"recommendations\":[]}")));

        assertThat(recommend()).isEmpty();
    }

    @Test
    void connectionErrorYieldsEmptyList() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/ai/covenant-status"))
                .willReturn(aResponse().withFault(
                        com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        assertThat(recommend()).isEmpty();
    }
}
