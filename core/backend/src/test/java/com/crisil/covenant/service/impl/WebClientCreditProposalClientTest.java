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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import com.crisil.covenant.domain.CreditProposalDetails;

class WebClientCreditProposalClientTest {

    private WireMockServer wireMock;
    private WebClientCreditProposalClient client;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        WebClient web = WebClient.builder().baseUrl(wireMock.baseUrl()).build();
        client = new WebClientCreditProposalClient(web);
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    @Test
    void returnsCpDetailsOn200() {
        wireMock.stubFor(get(urlEqualTo("/api/credit-proposals/CP-1001"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "cpId":"CP-1001",
                                  "name":"Acme",
                                  "businessSegment":"RETAIL",
                                  "sector":"Consumer",
                                  "approvalStatus":"APPROVED",
                                  "tags":["a","b"]
                                }
                                """)));

        CreditProposalDetails details = client.lookup("CP-1001");

        assertThat(details.cpId()).isEqualTo("CP-1001");
        assertThat(details.businessSegment()).isEqualTo("RETAIL");
        assertThat(details.safeTags()).containsExactly("a", "b");
        // rm/wrm absent from the payload ⇒ null (record stays tolerant of the older contract)
        assertThat(details.rm()).isNull();
        assertThat(details.wrm()).isNull();
        // obligorId absent ⇒ null: old upstream responses (pre-obligor) still deserialize.
        assertThat(details.obligorId()).isNull();
        // parentCpId absent ⇒ null: pre-lineage (US-MON-23) responses still deserialize.
        assertThat(details.parentCpId()).isNull();
    }

    @Test
    void parsesObligorIdWhenPresent() {
        wireMock.stubFor(get(urlEqualTo("/api/credit-proposals/CP-3001"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "cpId":"CP-3001",
                                  "name":"Acme",
                                  "obligorId":"OBL-77"
                                }
                                """)));

        CreditProposalDetails details = client.lookup("CP-3001");

        assertThat(details.obligorId()).isEqualTo("OBL-77");
    }

    @Test
    void parsesParentCpIdWhenPresent() {
        wireMock.stubFor(get(urlEqualTo("/api/credit-proposals/CP-4001"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "cpId":"CP-4001",
                                  "name":"Acme",
                                  "parentCpId":"CP-4000"
                                }
                                """)));

        CreditProposalDetails details = client.lookup("CP-4001");

        assertThat(details.parentCpId()).isEqualTo("CP-4000");
    }

    @Test
    void parsesRmAndWrmWhenPresent() {
        wireMock.stubFor(get(urlEqualTo("/api/credit-proposals/CP-2001"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "cpId":"CP-2001",
                                  "name":"Acme",
                                  "rm":{"userId":"u1","name":"Anil Kapoor","email":"anil@x.com"},
                                  "wrm":{"userId":"u2","name":"Meera Nair","email":"meera@x.com"}
                                }
                                """)));

        CreditProposalDetails details = client.lookup("CP-2001");

        assertThat(details.rm().name()).isEqualTo("Anil Kapoor");
        assertThat(details.rm().email()).isEqualTo("anil@x.com");
        assertThat(details.wrm().name()).isEqualTo("Meera Nair");
        assertThat(details.wrm().email()).isEqualTo("meera@x.com");
    }

    @Test
    void detailsWithNullTagsReturnsEmptyList() {
        CreditProposalDetails details = new CreditProposalDetails(
                "CP-1", null, null, null, null, null, null, null, null, null);
        assertThat(details.safeTags()).isEmpty();
    }

    @Test
    void throwsResourceNotFoundOn404() {
        wireMock.stubFor(get(urlEqualTo("/api/credit-proposals/MISSING"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> client.lookup("MISSING"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void throwsApiExceptionOn5xx() {
        wireMock.stubFor(get(urlEqualTo("/api/credit-proposals/CP-X"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.lookup("CP-X"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Credit Proposal service error");
    }
}
