package com.crisil.covenant.service.impl;

import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.config.WebClientsConfig;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import com.crisil.covenant.domain.DataCatalogResponse;
import com.crisil.covenant.service.DataCatalogClient;

@Component
public class WebClientDataCatalogClient implements DataCatalogClient {

    private final WebClient client;

    public WebClientDataCatalogClient(WebClient dataApiWebClient) {
        this.client = dataApiWebClient;
    }

    @Override
    public DataCatalogResponse fetchCatalog() {
        try {
            DataCatalogResponse response = client.get()
                    .uri("/api/data/catalog")
                    .retrieve()
                    .bodyToMono(DataCatalogResponse.class)
                    .block(WebClientsConfig.defaultTimeout());
            if (response == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "DATA_CATALOG_EMPTY",
                        "Data API returned an empty catalog");
            }
            return response;
        } catch (WebClientResponseException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DATA_CATALOG_FAILED",
                    "Data API catalog error " + e.getStatusCode());
        }
    }
}
