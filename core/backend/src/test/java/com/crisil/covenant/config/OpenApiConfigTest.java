package com.crisil.covenant.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
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

    @Test
    void everyOperationGetsTheSharedErrorResponses() {
        OpenAPI api = new OpenAPI()
                .path("/api/v1/covenants", new PathItem().get(new Operation()))
                .path("/api/v1/covenants/{id}", new PathItem().get(new Operation()
                        .responses(new ApiResponses().addApiResponse("200", new ApiResponse().description("ok")))));

        new OpenApiConfig().commonErrorResponsesCustomizer().customise(api);

        assertThat(api.getComponents().getSchemas()).containsKey(OpenApiConfig.ERROR_SCHEMA);
        assertThat(api.getPaths().get("/api/v1/covenants").getGet().getResponses()).containsKeys("400", "500")
                .doesNotContainKey("404");
        assertThat(api.getPaths().get("/api/v1/covenants/{id}").getGet().getResponses())
                .containsKeys("200", "400", "404", "500");
    }

    @Test
    void aDocumentWithoutPathsOrWithExistingSchemasIsLeftConsistent() {
        OpenAPI empty = new OpenAPI();
        new OpenApiConfig().commonErrorResponsesCustomizer().customise(empty);
        assertThat(empty.getComponents().getSchemas()).containsKey(OpenApiConfig.ERROR_SCHEMA);

        Schema<?> mine = new Schema<>().description("already registered");
        OpenAPI withSchema = new OpenAPI().components(new Components().addSchemas(OpenApiConfig.ERROR_SCHEMA, mine));
        new OpenApiConfig().commonErrorResponsesCustomizer().customise(withSchema);
        assertThat(withSchema.getComponents().getSchemas().get(OpenApiConfig.ERROR_SCHEMA)).isSameAs(mine);
    }
}
