package com.crisil.covenant.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/** Which credentials the DPS client sends: OAuth token, fixed token, or the dev headers. */
class WebClientsConfigDpsTest {

    private WireMockServer wireMock;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(get(urlPathEqualTo("/dps/v1/catalog")).willReturn(aResponse().withBody("[]")));
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    private void call(ClientsProperties props) {
        new WebClientsConfig().dpsWebClient(props, WebClient.builder(), Clock.systemUTC())
                .get().uri("/dps/v1/catalog").retrieve().bodyToMono(String.class).block();
    }

    private ClientsProperties props() {
        ClientsProperties props = new ClientsProperties();
        props.getDps().setBaseUrl(wireMock.baseUrl());
        return props;
    }

    @Test
    void oauthWinsOverAFixedToken() {
        wireMock.stubFor(post(urlPathEqualTo("/token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"fresh\",\"expires_in\":3600}")));
        ClientsProperties props = props();
        props.getDps().setServiceToken("fixed");
        props.getDps().getOauth().setTokenUri(wireMock.baseUrl() + "/token");
        props.getDps().getOauth().setClientId("core");
        props.getDps().getOauth().setClientSecret("secret");
        props.getDps().getOauth().setScope("api://covenant/.default");

        call(props);

        wireMock.verify(getRequestedFor(urlPathEqualTo("/dps/v1/catalog"))
                .withHeader("Authorization", equalTo("Bearer fresh")));
    }

    @Test
    void fixedTokenThenDevHeaders() {
        ClientsProperties fixed = props();
        fixed.getDps().setServiceToken("fixed");
        call(fixed);
        wireMock.verify(getRequestedFor(urlPathEqualTo("/dps/v1/catalog"))
                .withHeader("Authorization", equalTo("Bearer fixed")));

        wireMock.resetRequests();
        call(props());
        wireMock.verify(getRequestedFor(urlPathEqualTo("/dps/v1/catalog"))
                .withHeader("Authorization", absent())
                .withHeader("X-Roles", equalTo("SERVICE")));
    }
}
