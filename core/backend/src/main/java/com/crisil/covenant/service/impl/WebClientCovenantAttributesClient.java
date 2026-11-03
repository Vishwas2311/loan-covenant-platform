package com.crisil.covenant.service.impl;

import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.exception.ResourceNotFoundException;
import com.crisil.covenant.config.WebClientsConfig;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import com.crisil.covenant.domain.CovenantAttributes;
import com.crisil.covenant.service.CovenantAttributesClient;

@Component
public class WebClientCovenantAttributesClient implements CovenantAttributesClient {

    private final WebClient client;

    public WebClientCovenantAttributesClient(WebClient attributesWebClient) {
        this.client = attributesWebClient;
    }

    @Override
    public CovenantAttributes fetch(String cpId) {
        try {
            return client.get()
                    .uri("/api/attributes/{cpId}", cpId)
                    .retrieve()
                    .bodyToMono(CovenantAttributes.class)
                    .block(WebClientsConfig.defaultTimeout());
        } catch (WebClientResponseException.NotFound e) {
            throw new ResourceNotFoundException("Attributes not found for CP: " + cpId);
        } catch (WebClientResponseException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ATTRIBUTES_LOOKUP_FAILED",
                    "Attributes service error: " + e.getStatusCode());
        }
    }
}
