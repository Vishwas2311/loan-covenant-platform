package com.crisil.covenant.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class ClientCredentialsTokenProviderTest {

    private WireMockServer wireMock;
    private MutableClock clock;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        clock = new MutableClock(Instant.parse("2026-11-03T10:00:00Z"));
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    private ClientsProperties.ClientCredentials settings() {
        ClientsProperties.ClientCredentials s = new ClientsProperties.ClientCredentials();
        s.setTokenUri(wireMock.baseUrl() + "/tenant/oauth2/v2.0/token");
        s.setClientId("core-app");
        s.setClientSecret("s3cret");
        s.setScope("api://covenant/.default");
        return s;
    }

    private void tokenEndpointReturns(String token, int expiresIn) {
        wireMock.stubFor(post(urlPathEqualTo("/tenant/oauth2/v2.0/token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"" + token + "\",\"expires_in\":" + expiresIn + ",\"token_type\":\"Bearer\"}")));
    }

    @Test
    void fetchesWithClientCredentialsAndCachesUntilNearExpiry() {
        tokenEndpointReturns("t1", 3600);
        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(settings(), WebClient.builder(), clock);

        assertThat(provider.token()).isEqualTo("t1");
        clock.advance(Duration.ofMinutes(50));
        assertThat(provider.token()).isEqualTo("t1");
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/tenant/oauth2/v2.0/token"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withRequestBody(containing("client_id=core-app"))
                .withRequestBody(containing("scope=api%3A%2F%2Fcovenant%2F.default")));

        tokenEndpointReturns("t2", 3600);
        clock.advance(Duration.ofMinutes(9)); // 59 min: inside the refresh margin
        assertThat(provider.token()).isEqualTo("t2");
    }

    @Test
    void filterSendsTheBearerToken() {
        tokenEndpointReturns("t1", 3600);
        wireMock.stubFor(get(urlPathEqualTo("/dps/v1/catalog")).willReturn(aResponse().withBody("[]")));
        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(settings(), WebClient.builder(), clock);
        WebClient client = WebClient.builder().baseUrl(wireMock.baseUrl()).filter(provider.bearerFilter()).build();

        client.get().uri("/dps/v1/catalog").retrieve().bodyToMono(String.class).block();

        wireMock.verify(getRequestedFor(urlPathEqualTo("/dps/v1/catalog"))
                .withHeader("Authorization", equalTo("Bearer t1")));
    }

    @Test
    void refusedOrEmptyTokenResponsesFailClearlyWithoutEchoingTheSecret() {
        wireMock.stubFor(post(urlPathEqualTo("/tenant/oauth2/v2.0/token"))
                .willReturn(aResponse().withStatus(401).withBody("invalid_client s3cret")));
        ClientCredentialsTokenProvider refused = new ClientCredentialsTokenProvider(settings(), WebClient.builder(), clock);
        assertThatThrownBy(refused::token)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageNotContaining("s3cret");

        wireMock.stubFor(post(urlPathEqualTo("/tenant/oauth2/v2.0/token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{}")));
        ClientCredentialsTokenProvider empty = new ClientCredentialsTokenProvider(settings(), WebClient.builder(), clock);
        assertThatThrownBy(empty::token).hasMessageContaining("no access_token");
    }

    @Test
    void incompleteSettingsAreRefusedAtStartup() {
        ClientsProperties.ClientCredentials s = settings();
        s.setClientSecret(" ");
        assertThatThrownBy(() -> new ClientCredentialsTokenProvider(s, WebClient.builder(), clock))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new ClientsProperties.ClientCredentials().isConfigured()).isFalse();
        s.setTokenUri(" ");
        assertThat(s.isConfigured()).isFalse();
    }

    @Test
    void missingExpiryDefaultsToFiveMinutes() {
        wireMock.stubFor(post(urlPathEqualTo("/tenant/oauth2/v2.0/token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"access_token\":\"t1\"}")));
        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(settings(), WebClient.builder(), clock);
        provider.token();
        clock.advance(Duration.ofMinutes(3));
        provider.token();
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/tenant/oauth2/v2.0/token")));
        clock.advance(Duration.ofMinutes(1)); // 4 min: within 60 s of the 5-minute default
        provider.token();
        wireMock.verify(2, postRequestedFor(urlPathEqualTo("/tenant/oauth2/v2.0/token")));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
