package com.crisil.covenant.service.impl;

import com.crisil.covenant.config.WebClientsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import com.crisil.covenant.domain.WorkflowCase;
import com.crisil.covenant.dto.WorkflowCaseRequest;
import com.crisil.covenant.exception.WorkflowException;
import com.crisil.covenant.service.WorkflowClient;

@Component
public class WebClientWorkflowClient implements WorkflowClient {

    private static final Logger LOG = LoggerFactory.getLogger(WebClientWorkflowClient.class);

    private final WebClient client;

    public WebClientWorkflowClient(WebClient workflowWebClient) {
        this.client = workflowWebClient;
    }

    @Override
    public WorkflowCase startCase(WorkflowCaseRequest request) {
        try {
            return client.post()
                    .uri("/api/runtime/cases")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(WorkflowCase.class)
                    .block(WebClientsConfig.defaultTimeout());
        } catch (WebClientException ex) {
            throw new WorkflowException(
                    "Workflow service unreachable: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void submitCaseStep1(String caseId) {
        // TODO(workflow): downstream case call 1, keyed by case_id — real URI/payload TBD.
        LOG.info("Workflow case step 1 placeholder for case {}", sanitise(caseId));
    }

    @Override
    public void submitCaseStep2(String caseId) {
        // TODO(workflow): downstream case call 2, keyed by case_id — real URI/payload TBD.
        LOG.info("Workflow case step 2 placeholder for case {}", sanitise(caseId));
    }

    @Override
    public void submitCaseStep3(String caseId) {
        // TODO(workflow): downstream case call 3, keyed by case_id — real URI/payload TBD.
        LOG.info("Workflow case step 3 placeholder for case {}", sanitise(caseId));
    }

    /** CR/LF-strip an external caseId so it is taint-safe at the log sink. */
    private static String sanitise(String value) {
        return value == null ? "null" : value.replace('\n', '_').replace('\r', '_');
    }
}
