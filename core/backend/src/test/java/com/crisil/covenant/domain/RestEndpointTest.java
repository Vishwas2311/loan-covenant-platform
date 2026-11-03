package com.crisil.covenant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RestEndpointTest {

    @Test
    void exposesBaseUrlAndValuesPath() {
        RestEndpoint endpoint = new RestEndpoint("http://feed.example", "/v2/values");
        assertThat(endpoint.baseUrl()).isEqualTo("http://feed.example");
        assertThat(endpoint.valuesPath()).isEqualTo("/v2/values");
    }

    @Test
    void allowsNullBaseUrlForGlobalFallback() {
        RestEndpoint endpoint = new RestEndpoint(null, "/api/data/values");
        assertThat(endpoint.baseUrl()).isNull();
        assertThat(endpoint.valuesPath()).isEqualTo("/api/data/values");
    }
}
