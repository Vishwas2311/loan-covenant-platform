package com.crisil.covenant.service.impl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.dto.NotificationRequest;
import com.crisil.covenant.dto.NotificationResult;
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

class WebClientNotificationClientTest {

    private WireMockServer wireMock;
    private LoopResources loopResources;
    private WebClientNotificationClient client;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        // Dedicated, non-pooled reactor-netty resources so this test is immune to ordering: a fresh
        // connection per request (no pool poisoned by the reset/timeout cases) and a private event
        // loop that Spring's global-resource disposal cannot touch.
        loopResources = LoopResources.create("notif-test", 1, true);
        HttpClient httpClient = HttpClient.create(ConnectionProvider.newConnection())
                .runOn(loopResources)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                .responseTimeout(Duration.ofSeconds(2));
        // Use 127.0.0.1 (not "localhost") to avoid an IPv6 (::1) resolution stall vs WireMock's bind.
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
        client = new WebClientNotificationClient(web, cbr, rr, tlr);
    }

    @AfterEach
    void stop() {
        wireMock.stop();
        loopResources.disposeLater().block(Duration.ofSeconds(5));
    }

    private NotificationRequest request() {
        return new NotificationRequest("COV", "Cov_Template", "rm@x.com", "wrm@x.com",
                new NotificationRequest.Variables(List.of(Map.of("CP_ID", "CP-1"))), "HIGH");
    }

    @Test
    void returnsSuccessOn2xx() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/notifications"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"QUEUED\"}")));

        NotificationResult result = client.send(request());

        assertThat(result.success()).isTrue();
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.responseBody()).contains("QUEUED");
        assertThat(result.error()).isNull();
    }

    @Test
    void returnsFailedResultOn5xxWithoutThrowing() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/notifications"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        NotificationResult result = client.send(request());

        assertThat(result.success()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(500);
        assertThat(result.error()).isNotNull();
    }

    @Test
    void returnsFailedResultOnTimeout() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/notifications"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(4000) // longer than the 2s reactor-netty response timeout
                        .withBody("{\"status\":\"QUEUED\"}")));

        NotificationResult result = client.send(request());

        assertThat(result.success()).isFalse();
    }

    @Test
    void returnsFailedResultOnConnectionError() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/notifications"))
                .willReturn(aResponse().withFault(
                        com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        NotificationResult result = client.send(request());

        assertThat(result.success()).isFalse();
    }
}
