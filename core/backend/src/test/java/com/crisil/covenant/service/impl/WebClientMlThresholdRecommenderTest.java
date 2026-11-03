package com.crisil.covenant.service.impl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.dto.MlThresholdRecommendation;
import com.crisil.covenant.dto.MlThresholdRequest;
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
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

class WebClientMlThresholdRecommenderTest {

    private WireMockServer wireMock;
    private LoopResources loopResources;
    private WebClientMlThresholdRecommender recommender;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        // Dedicated, non-pooled reactor-netty resources so this test is immune to ordering: a fresh
        // connection per request (no pool to be poisoned by the reset/timeout cases) and a private
        // event loop that Spring's global-resource disposal on @SpringBootTest shutdown cannot touch.
        loopResources = LoopResources.create("ml-rec-test", 1, true);
        // Detect a slow/unreachable ML service with reactor-netty's own connect + response timeouts
        // (deterministic, thread-independent) rather than leaning on the resilience timer, which is
        // flaky under a shared surefire fork. The resilience time limiter stays as a generous backstop.
        HttpClient httpClient = HttpClient.create(ConnectionProvider.newConnection())
                .runOn(loopResources)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                .responseTimeout(Duration.ofSeconds(2));
        // Use 127.0.0.1 (not "localhost") to avoid an IPv6 (::1) resolution stall vs WireMock's bind.
        WebClient client = WebClient.builder()
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
        recommender = new WebClientMlThresholdRecommender(client, cbr, rr, tlr);
    }

    @AfterEach
    void stop() {
        wireMock.stop();
        loopResources.disposeLater().block(Duration.ofSeconds(5));
    }

    private MlThresholdRequest request() {
        return new MlThresholdRequest("CP-1", Map.of(), List.of(), List.of());
    }

    @Test
    void returnsRecommendationsOn200() {
        wireMock.stubFor(post(urlEqualTo("/threshold-recommendations"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"covenantMasterId":1,"recommendedExpression":"DSCR > 1.6",
                                  "confidence":0.90,"rationale":"tighter"}]
                                """)));

        List<MlThresholdRecommendation> out = recommender.recommend(request());

        assertThat(out).hasSize(1);
        assertThat(out.get(0).covenantMasterId()).isEqualTo("1");
        assertThat(out.get(0).recommendedExpression()).isEqualTo("DSCR > 1.6");
        assertThat(out.get(0).confidence()).isEqualByComparingTo("0.90");
    }

    @Test
    void fallsBackToEmptyListOn5xx() {
        wireMock.stubFor(post(urlEqualTo("/threshold-recommendations"))
                .willReturn(aResponse().withStatus(500)));

        assertThat(recommender.recommend(request())).isEmpty();
    }

    @Test
    void fallsBackToEmptyListOnTimeout() {
        wireMock.stubFor(post(urlEqualTo("/threshold-recommendations"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(4000) // longer than the 2s reactor-netty response timeout
                        .withBody("[]")));

        assertThat(recommender.recommend(request())).isEmpty();
    }

    @Test
    void fallsBackToEmptyListOnConnectionError() {
        wireMock.stubFor(post(urlEqualTo("/threshold-recommendations"))
                .willReturn(aResponse().withFault(
                        com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        assertThat(recommender.recommend(request())).isEmpty();
    }
}
