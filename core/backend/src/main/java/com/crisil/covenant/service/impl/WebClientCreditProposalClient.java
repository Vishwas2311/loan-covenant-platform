package com.crisil.covenant.service.impl;

import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.exception.ResourceNotFoundException;
import com.crisil.covenant.config.WebClientsConfig;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import com.crisil.covenant.domain.CreditProposalDetails;
import com.crisil.covenant.service.CreditProposalClient;

@Component
public class WebClientCreditProposalClient implements CreditProposalClient {

    private final WebClient client;

    public WebClientCreditProposalClient(WebClient creditProposalWebClient) {
        this.client = creditProposalWebClient;
    }

    @Override
    public CreditProposalDetails lookup(String cpId) {
        try {
            return client.get()
                    .uri("/api/credit-proposals/{cpId}", cpId)
                    .retrieve()
                    .bodyToMono(CreditProposalDetails.class)
                    .block(WebClientsConfig.defaultTimeout());
        } catch (WebClientResponseException.NotFound e) {
            throw new ResourceNotFoundException("CP not found: " + cpId);
        } catch (WebClientResponseException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "CP_LOOKUP_FAILED",
                    "Credit Proposal service error: " + e.getStatusCode());
        }
    }
}
