package com.crisil.covenant.config;

import com.crisil.covenant.dto.ErrorResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 / Swagger UI configuration (served by springdoc-openapi). The document is exposed at
 * {@code /v3/api-docs} (JSON) and browsable at {@code /swagger-ui.html}; per-operation metadata lives
 * on the controllers via {@code @Tag}/{@code @Operation}/{@code @ApiResponse} annotations.
 */
@Configuration
public class OpenApiConfig {

    /** Component name of the shared error payload; kept in sync with {@link ErrorResponse}. */
    static final String ERROR_SCHEMA = "ErrorResponse";
    private static final String ERROR_REF = "#/components/schemas/" + ERROR_SCHEMA;
    private static final String JSON = "application/json";

    @Bean
    public OpenAPI covenantOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Covenant Monitoring API")
                        .description("REST API for the Covenant Monitoring System: author covenant "
                                + "rule templates and measures, recommend and apply covenants to "
                                + "Credit Proposals (CPs), register CP-keyed data sources, and "
                                + "run/assess compliance with Waiver/Extension dispositions and "
                                + "append-only audit trails. Every error response uses the shared "
                                + "ErrorResponse schema (code, message, optional field-level "
                                + "details, timestamp).")
                        .version("0.1.0")
                        .termsOfService("https://crisil.com")
                        .contact(new Contact()
                                .name("Covenant Monitoring Team")
                                .email("covenant-support@crisil.com")
                                .url("https://crisil.com"))
                        .license(new License().name("Internal").url("https://crisil.com")))
                .servers(List.of(
                        new Server().url("/").description("This server (relative)"),
                        new Server().url("http://localhost:9310").description("Local development")));
    }

    /**
     * Documents, on every generated operation, the error responses the {@code GlobalExceptionHandler}
     * can return for any endpoint — all sharing the {@link ErrorResponse} schema (registered once):
     * <ul>
     *   <li>{@code 400} (validation / illegal argument) and {@code 500} (unexpected) — on every
     *       operation;</li>
     *   <li>{@code 404} — on every operation whose path carries a resource identifier (a
     *       <code>{var}</code> template), since those can resolve to a missing resource.</li>
     * </ul>
     * Doing this here (rather than per method) keeps the controller annotations focused on success
     * responses, and — crucially — avoids declaring a method-level {@code @ApiResponse} for a mere
     * error code, which would make springdoc drop the auto-derived 2xx body schema.
     */
    @Bean
    public OpenApiCustomizer commonErrorResponsesCustomizer() {
        return openApi -> {
            registerErrorSchema(openApi);
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, pathItem) -> {
                boolean hasPathVariable = path.indexOf('{') >= 0;
                pathItem.readOperations().forEach(operation -> {
                    ApiResponses responses = operation.getResponses();
                    if (responses == null) {
                        responses = new ApiResponses();
                        operation.setResponses(responses);
                    }
                    responses.putIfAbsent("400",
                            errorResponse("Validation failed or the request was malformed"));
                    if (hasPathVariable) {
                        responses.putIfAbsent("404", errorResponse("The requested resource was not found"));
                    }
                    responses.putIfAbsent("500", errorResponse("Unexpected server error"));
                });
            });
        };
    }

    private static void registerErrorSchema(OpenAPI openApi) {
        Components components = openApi.getComponents();
        if (components == null) {
            components = new Components();
            openApi.setComponents(components);
        }
        // Register ErrorResponse and every nested schema it references (e.g. FieldError). Use
        // putIfAbsent so we fill in any schema springdoc has not already registered — otherwise a
        // pre-registered ErrorResponse could leave its nested FieldError as a dangling $ref.
        final Components target = components;
        ModelConverters.getInstance().readAll(ErrorResponse.class)
                .forEach((name, schema) -> {
                    if (target.getSchemas() == null || !target.getSchemas().containsKey(name)) {
                        target.addSchemas(name, schema);
                    }
                });
    }

    private static ApiResponse errorResponse(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(JSON,
                        new MediaType().schema(new Schema<>().$ref(ERROR_REF))));
    }
}
