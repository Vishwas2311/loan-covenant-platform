package com.crisil.covenant.service.impl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.exception.ResourceNotFoundException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import com.crisil.covenant.domain.CovenantAttributes;

class WebClientCovenantAttributesClientTest {

    private WireMockServer wireMock;
    private WebClientCovenantAttributesClient client;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        client = new WebClientCovenantAttributesClient(
                WebClient.builder().baseUrl(wireMock.baseUrl()).build());
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    @Test
    void returnsAttributesOn200() {
        wireMock.stubFor(get(urlEqualTo("/api/attributes/CP-1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"cpId":"CP-1","businessSegment":"RETAIL","sector":"X","attributes":{"k":"v"}}
                                """)));

        CovenantAttributes a = client.fetch("CP-1");

        assertThat(a.businessSegment()).isEqualTo("RETAIL");
        assertThat(a.safeAttributes()).containsEntry("k", "v");
    }

    @Test
    void safeAttributesEmptyWhenNull() {
        assertThat(new CovenantAttributes("X", null, null, null).safeAttributes())
                .isEqualTo(Map.of());
    }

    @Test
    void throwsResourceNotFoundOn404() {
        wireMock.stubFor(get(urlEqualTo("/api/attributes/MISSING"))
                .willReturn(aResponse().withStatus(404)));
        assertThatThrownBy(() -> client.fetch("MISSING"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void throwsApiExceptionOn5xx() {
        wireMock.stubFor(get(urlEqualTo("/api/attributes/CP-X"))
                .willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client.fetch("CP-X"))
                .isInstanceOf(ApiException.class);
    }
}
