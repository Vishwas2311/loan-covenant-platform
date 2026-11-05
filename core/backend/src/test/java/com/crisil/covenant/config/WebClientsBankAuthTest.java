package com.crisil.covenant.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/** GAP-06: bank-service clients (here the notification service) send the credentials configured for them. */
class WebClientsBankAuthTest {

    private WireMockServer wireMock;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(post(urlPathEqualTo("/api/v1/notifications")).willReturn(aResponse().withBody("{}")));
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    private void send(ClientsProperties props) {
        new WebClientsConfig().notificationWebClient(props, WebClient.builder(), Clock.systemUTC())
                .post().uri("/api/v1/notifications").bodyValue("{}").retrieve().bodyToMono(String.class).block();
    }

    private ClientsProperties props() {
        ClientsProperties props = new ClientsProperties();
        props.getNotification().setBaseUrl(wireMock.baseUrl());
        return props;
    }

    @Test
    void oauthTokenIsFetchedAndSent() {
        wireMock.stubFor(post(urlPathEqualTo("/token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"bank-token\",\"expires_in\":3600}")));
        ClientsProperties props = props();
        props.getNotification().getOauth().setTokenUri(wireMock.baseUrl() + "/token");
        props.getNotification().getOauth().setClientId("core");
        props.getNotification().getOauth().setClientSecret("secret");
        props.getNotification().getOauth().setScope("api://notifications/.default");

        send(props);

        wireMock.verify(postRequestedFor(urlPathEqualTo("/api/v1/notifications"))
                .withHeader("Authorization", equalTo("Bearer bank-token")));
    }

    @Test
    void aFixedTokenIsSentWhenNoOauthIsConfigured() {
        ClientsProperties props = props();
        props.getNotification().setServiceToken("fixed");

        send(props);

        wireMock.verify(postRequestedFor(urlPathEqualTo("/api/v1/notifications"))
                .withHeader("Authorization", equalTo("Bearer fixed")));
    }

    @Test
    void withNoCredentialsConfiguredNoneAreSent() {
        send(props());

        wireMock.verify(postRequestedFor(urlPathEqualTo("/api/v1/notifications"))
                .withHeader("Authorization", absent()));
    }
}
