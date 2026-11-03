package com.crisil.covenant.domain;

/**
 * WS4: where to reach a REST data source's consolidated as-of-aware values endpoint. Each source
 * carries its own {@code baseUrl} so it is routed to its own host instead of a single global base
 * URL, plus the {@code valuesPath} of the endpoint. A blank {@code baseUrl} means "use the data-API
 * client's configured default base URL"; the {@code valuesPath} is resolved against whichever base
 * applies.
 *
 * <p>{@code auth} names the credentials to send: {@code "dps"} uses the Document Processing Service
 * client (service token or SERVICE dev headers, and its base URL when {@code baseUrl} is blank); null
 * sends none, as before.
 */
public record RestEndpoint(String baseUrl, String valuesPath, String auth) {

    public static final String AUTH_DPS = "dps";

    public RestEndpoint(String baseUrl, String valuesPath) {
        this(baseUrl, valuesPath, null);
    }
}
