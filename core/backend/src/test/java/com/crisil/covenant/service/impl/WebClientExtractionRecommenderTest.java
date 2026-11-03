package com.crisil.covenant.service.impl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.dto.ExtractionRecommendation;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
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

class WebClientExtractionRecommenderTest {

    private WireMockServer wireMock;
    private LoopResources loopResources;
    private WebClientExtractionRecommender recommender;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        // Same isolation as WebClientMlThresholdRecommenderTest: non-pooled connections, private loop.
        loopResources = LoopResources.create("dps-rec-test", 1, true);
        HttpClient httpClient = HttpClient.create(ConnectionProvider.newConnection())
                .runOn(loopResources)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                .responseTimeout(Duration.ofSeconds(2));
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
        recommender = new WebClientExtractionRecommender(client, CircuitBreakerRegistry.ofDefaults(), rr, tlr);
    }

    @AfterEach
    void stop() {
        wireMock.stop();
        loopResources.disposeLater().block(Duration.ofSeconds(5));
    }

    @Test
    void returnsRecommendationsOn200() {
        wireMock.stubFor(post(urlEqualTo("/dps/v1/recommendations"))
                .withRequestBody(equalToJson("{\"cpId\":\"CP-1\"}"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"covenantMasterId":"7","recommendedExpression":"DSCR >= 1.25",
                                  "confidence":0.93,"rationale":"clause 7.1","frequency":"QUARTERLY",
                                  "source":{"documentId":"D1","fileId":"F1","page":12,"quote":"q"},
                                  "customCovenant":null,"extra":"ignored"},
                                 {"covenantMasterId":null,"recommendedExpression":null,"confidence":0.6,
                                  "source":{"documentId":"D1","fileId":"F1","page":3,"quote":"q2"},
                                  "customCovenant":{"name":"Insurance","definition":"d","assessmentType":"MANUAL"}}]
                                """)));

        List<ExtractionRecommendation> out = recommender.recommend("CP-1");

        assertThat(out).hasSize(2);
        assertThat(out.get(0).covenantMasterId()).isEqualTo("7");
        assertThat(out.get(0).confidence()).isEqualTo(0.93);
        assertThat(out.get(0).source().page()).isEqualTo(12);
        assertThat(out.get(1).customCovenant().name()).isEqualTo("Insurance");
    }

    @Test
    void fallsBackToEmptyListOn5xx() {
        wireMock.stubFor(post(urlEqualTo("/dps/v1/recommendations")).willReturn(aResponse().withStatus(503)));
        assertThat(recommender.recommend("CP-1")).isEmpty();
    }

    @Test
    void fallsBackToEmptyListOnConnectionError() {
        wireMock.stubFor(post(urlEqualTo("/dps/v1/recommendations"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThat(recommender.recommend("CP-1")).isEmpty();
    }
}
