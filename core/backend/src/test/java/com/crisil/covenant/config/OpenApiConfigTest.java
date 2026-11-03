package com.crisil.covenant.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

class OpenApiConfigTest {

    @Test
    void buildsOpenApiBean() {
        OpenAPI api = new OpenApiConfig().covenantOpenApi();
        assertThat(api.getInfo()).isNotNull();
        assertThat(api.getInfo().getTitle()).isEqualTo("Covenant Monitoring API");
        assertThat(api.getInfo().getVersion()).isEqualTo("0.1.0");
        assertThat(api.getInfo().getLicense().getName()).isEqualTo("Internal");
    }
}
