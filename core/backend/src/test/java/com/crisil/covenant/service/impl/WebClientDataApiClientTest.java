package com.crisil.covenant.service.impl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.covenant.domain.DataSeriesResponse;
import com.crisil.covenant.domain.RestEndpoint;
import com.crisil.covenant.exception.ApiException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Verifies the single as-of-aware {@code fetchValues} contract (WS3) and per-source routing (WS4):
 * an absolute {@code baseUrl} bypasses the client's global base URL, a blank one falls back to it, a
 * windowed read sends {@code from} while a point-in-time read omits it, and {@code sourceChangedAt}
 * round-trips. The default per-request WebClient codecs deserialise the JSON (incl. the java.time
 * fields), so no Spring context is needed.
 */
class WebClientDataApiClientTest {

    private static final String VALUES_PATH = "/api/data/values";

    private WireMockServer wireMock;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    /** A client whose global base URL is {@code baseUrl} (used when the endpoint doesn't route absolutely). */
    private WebClientDataApiClient clientWithGlobalBase(String baseUrl) {
        return new WebClientDataApiClient(WebClient.builder().baseUrl(baseUrl).build(),
                WebClient.builder().baseUrl("http://localhost:1").build(), List.of(wireMock.baseUrl()));
    }

    @Test
    void dpsSourceUsesTheAuthenticatedDpsClient() {
        wireMock.stubFor(post(urlPathEqualTo("/dps/v1/values"))
                .withHeader("X-Roles", com.github.tomakehurst.wiremock.client.WireMock.equalTo("SERVICE"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"cpId":"CP-1","points":[{"asOfDate":"2026-03-31","values":{"DPS_EBITDA":5.0}}]}
                                """)));
        WebClient dps = WebClient.builder().baseUrl(wireMock.baseUrl())
                .defaultHeader("X-User", "covenant-core").defaultHeader("X-Roles", "SERVICE").build();
        WebClientDataApiClient client = new WebClientDataApiClient(
                WebClient.builder().baseUrl("http://localhost:1").build(), dps, List.of());

        DataSeriesResponse relative = client.fetchValues(
                new RestEndpoint(null, "/dps/v1/values", RestEndpoint.AUTH_DPS), "CP-1",
                null, LocalDate.parse("2026-03-31"), List.of("DPS_EBITDA"));
        // A baseUrl on a DPS source is ignored: the service credentials only go to the DPS client's host.
        DataSeriesResponse absolute = client.fetchValues(
                new RestEndpoint("http://attacker.example", "/dps/v1/values", RestEndpoint.AUTH_DPS), "CP-1",
                null, LocalDate.parse("2026-03-31"), List.of("DPS_EBITDA"));

        assertThat(relative.safePoints().get(0).safeValues()).containsEntry("DPS_EBITDA", 5.0);
        assertThat(absolute.safePoints()).hasSize(1);
    }

    @Test
    void windowedReadMapsPointsMissingKeysAndSourceChangedAt() {
        wireMock.stubFor(post(urlPathEqualTo(VALUES_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"cpId":"CP-1","points":[
                                  {"asOfDate":"2025-12-31","values":{"DSCR":1.5}},
                                  {"asOfDate":"2026-03-31","values":{"DSCR":1.9}}
                                ],"missingKeys":["ICR"],"sourceChangedAt":"2026-03-31T12:00:00Z"}
                                """)));
        WebClientDataApiClient client = clientWithGlobalBase(wireMock.baseUrl());

        // baseUrl null => relative path against the client's global base URL; from non-null => windowed.
        DataSeriesResponse out = client.fetchValues(new RestEndpoint(null, VALUES_PATH), "CP-1",
                LocalDate.parse("2024-01-01"), LocalDate.parse("2026-03-31"), List.of("DSCR", "ICR"));

        assertThat(out.safePoints()).hasSize(2);
        assertThat(out.safePoints().get(0).asOfDate()).isEqualTo(LocalDate.parse("2025-12-31"));
        assertThat(out.safePoints().get(0).safeValues()).containsEntry("DSCR", 1.5);
        assertThat(out.safeMissingKeys()).containsExactly("ICR");
        assertThat(out.sourceChangedAt()).isEqualTo(Instant.parse("2026-03-31T12:00:00Z"));
        // A windowed read carries the lower bound in the body.
        wireMock.verify(postRequestedFor(urlPathEqualTo(VALUES_PATH)).withRequestBody(containing("\"from\"")));
    }

    @Test
    void absoluteBaseUrlBypassesGlobalBaseAndOmitsFromForPointInTime() {
        wireMock.stubFor(post(urlPathEqualTo(VALUES_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"cpId":"CP-1","points":[{"asOfDate":"2026-03-31","values":{"DSCR":1.9}}]}
                                """)));
        // Global base is bogus; only absolute per-source routing can reach the stub.
        WebClientDataApiClient client = clientWithGlobalBase("http://localhost:1");

        // baseUrl set => absolute URI; valuesPath null => default path; from null => point-in-time.
        DataSeriesResponse out = client.fetchValues(new RestEndpoint(wireMock.baseUrl(), null), "CP-1",
                null, LocalDate.parse("2026-03-31"), List.of("DSCR"));

        assertThat(out.safePoints()).hasSize(1);
        // A point-in-time read omits the lower bound entirely.
        wireMock.verify(postRequestedFor(urlPathEqualTo(VALUES_PATH)).withRequestBody(notContaining("\"from\"")));
    }

    @Test
    void blankBaseUrlAndBlankPathFallBackToRelativeDefault() {
        wireMock.stubFor(post(urlPathEqualTo(VALUES_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"cpId":"CP-1","points":[],"missingKeys":[]}
                                """)));
        WebClientDataApiClient client = clientWithGlobalBase(wireMock.baseUrl());

        // Blank baseUrl => relative; blank valuesPath => default "/api/data/values".
        DataSeriesResponse out = client.fetchValues(new RestEndpoint("", "   "), "CP-1",
                LocalDate.parse("2024-01-01"), LocalDate.parse("2026-03-31"), List.of("DSCR"));

        assertThat(out.safePoints()).isEmpty();
    }

    @Test
    void baseUrlOutsideTheAllowlistIsRefused() {
        WebClientDataApiClient client = clientWithGlobalBase(wireMock.baseUrl());

        for (String base : new String[] {"http://169.254.169.254", wireMock.baseUrl() + "@attacker.example",
                                         "http://attacker.example"}) {
            assertThatThrownBy(() -> client.fetchValues(new RestEndpoint(base, VALUES_PATH), "CP-1",
                    null, LocalDate.now(), List.of("k")))
                    .isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo("DATA_SOURCE_HOST_NOT_ALLOWED");
        }
    }

    @Test
    void valuesPathMustBeAPlainPath() {
        WebClientDataApiClient client = clientWithGlobalBase(wireMock.baseUrl());

        for (String path : new String[] {"http://attacker.example/x", "//attacker.example/x", "api/values",
                                         "/api/../admin", "/api\\values"}) {
            assertThatThrownBy(() -> client.fetchValues(new RestEndpoint(null, path), "CP-1",
                    null, LocalDate.now(), List.of("k")))
                    .isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo("DATA_SOURCE_PATH_INVALID");
        }
    }

    @Test
    void throwsDataApiEmptyOnNullBody() {
        wireMock.stubFor(post(urlPathEqualTo(VALUES_PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")));
        WebClientDataApiClient client = clientWithGlobalBase(wireMock.baseUrl());

        assertThatThrownBy(() -> client.fetchValues(new RestEndpoint(null, VALUES_PATH), "CP-1",
                null, LocalDate.now(), List.of("k")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("empty body");
    }

    @Test
    void throwsDataApiFailedOnServerError() {
        wireMock.stubFor(post(urlPathEqualTo(VALUES_PATH))
                .willReturn(aResponse().withStatus(500)));
        WebClientDataApiClient client = clientWithGlobalBase(wireMock.baseUrl());

        assertThatThrownBy(() -> client.fetchValues(new RestEndpoint(null, VALUES_PATH), "CP-1",
                LocalDate.now().minusYears(1), LocalDate.now(), List.of("k")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Data API error");
    }

    @Test
    void safePointsMissingKeysAndSourceChangedAtWhenNull() {
        DataSeriesResponse r = new DataSeriesResponse("CP-1", null, null, null);
        assertThat(r.safePoints()).isEmpty();
        assertThat(r.safeMissingKeys()).isEmpty();
        assertThat(r.sourceChangedAt()).isNull();
    }
}
