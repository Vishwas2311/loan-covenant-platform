package com.crisil.covenant.service.impl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.covenant.domain.WorkflowCase;
import com.crisil.covenant.dto.WorkflowCaseRequest;
import com.crisil.covenant.exception.WorkflowException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class WebClientWorkflowClientTest {

    private WireMockServer wireMock;
    private WebClientWorkflowClient client;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        client = new WebClientWorkflowClient(WebClient.builder().baseUrl(wireMock.baseUrl()).build());
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    @Test
    void startCaseParsesCaseIdOn200() {
        wireMock.stubFor(post(urlEqualTo("/api/runtime/cases"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"caseId\":\"case-42\"}")));

        WorkflowCase created = client.startCase(new WorkflowCaseRequest(
                "Covenant_Monitoring", new WorkflowCaseRequest.Variables("rm-anil")));

        assertThat(created.caseId()).isEqualTo("case-42");
    }

    @Test
    void wrapsConnectionErrorAsWorkflowException() {
        WebClientWorkflowClient broken = new WebClientWorkflowClient(
                WebClient.builder().baseUrl("http://localhost:1").build());
        assertThatThrownBy(() -> broken.startCase(new WorkflowCaseRequest(
                "Covenant_Monitoring", new WorkflowCaseRequest.Variables("rm-anil"))))
                .isInstanceOf(WorkflowException.class);
    }

    @Test
    void downstreamCaseStepPlaceholdersRunWithoutError() {
        // Placeholders today (log only); exercised so all three are covered and proven non-throwing,
        // including both the non-null and null branches of the caseId sanitiser.
        assertThatCode(() -> {
            client.submitCaseStep1("case-1");
            client.submitCaseStep2("case-\n2");
            client.submitCaseStep3(null);
        }).doesNotThrowAnyException();
    }

    @Test
    void workflowExceptionRetainsCause() {
        WorkflowException ex = new WorkflowException("msg", new RuntimeException("c"));
        assertThat(ex.getMessage()).isEqualTo("msg");
        assertThat(ex.getCause()).isNotNull();
    }
}
