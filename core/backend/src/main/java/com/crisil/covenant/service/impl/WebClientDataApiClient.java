package com.crisil.covenant.service.impl;

import com.crisil.covenant.config.WebClientsConfig;
import com.crisil.covenant.domain.DataSeriesResponse;
import com.crisil.covenant.domain.RestEndpoint;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.service.DataApiClient;
import java.net.URI;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * WebClient-backed {@link DataApiClient}. Every read goes through the single POST
 * {@code valuesPath} endpoint (WS3). Per-source routing (WS4): when the source supplies a
 * {@code baseUrl} the request is issued against an absolute URI, which bypasses the client's global
 * base URL so each source can live on its own host; a blank {@code baseUrl} falls back to the
 * relative path against the client's configured default base URL. A source with
 * {@code auth = "dps"} is called through the Document Processing Service client so the request
 * carries the service credentials DPS requires (C2).
 */
@Component
public class WebClientDataApiClient implements DataApiClient {

    private static final String DEFAULT_VALUES_PATH = "/api/data/values";

    private final WebClient client;
    private final WebClient dpsClient;
    private final List<String> allowedBaseUrls;

    /**
     * @param allowedBaseUrls the only URL prefixes a source's {@code baseUrl} may point at. Anyone who
     *     can edit data sources could otherwise make Core call any host it can reach (SSRF).
     */
    public WebClientDataApiClient(@Qualifier("dataApiWebClient") WebClient dataApiWebClient,
                                  @Qualifier("dpsWebClient") WebClient dpsWebClient,
                                  @Value("${covenant.data-sources.allowed-rest-base-urls:}")
                                  List<String> allowedBaseUrls) {
        this.client = dataApiWebClient;
        this.dpsClient = dpsWebClient;
        this.allowedBaseUrls = allowedBaseUrls.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public DataSeriesResponse fetchValues(RestEndpoint endpoint, String cpId, LocalDate from,
                                          LocalDate to, List<String> keys) {
        Map<String, Object> body = new HashMap<>();
        body.put("cpId", cpId);
        body.put("to", to.toString());
        body.put("keys", keys);
        if (from != null) {
            body.put("from", from.toString()); // omitted for a point-in-time read (latest <= to)
        }
        try {
            DataSeriesResponse response = requestTo(endpoint)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(DataSeriesResponse.class)
                    .block(WebClientsConfig.defaultTimeout());
            if (response == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "DATA_API_EMPTY",
                        "Data API returned empty body for " + cpId);
            }
            return response;
        } catch (WebClientResponseException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DATA_API_FAILED",
                    "Data API error " + e.getStatusCode() + " for " + cpId);
        }
    }

    /**
     * POST spec pointed at the source's own endpoint. A non-blank {@code baseUrl} yields an absolute
     * URI so the request bypasses the client's global base URL (per-source routing); a blank one falls
     * back to the relative {@code valuesPath} resolved against the client's configured base URL.
     */
    private WebClient.RequestBodySpec requestTo(RestEndpoint endpoint) {
        String valuesPath = endpoint.valuesPath() == null || endpoint.valuesPath().isBlank()
                ? DEFAULT_VALUES_PATH : endpoint.valuesPath();
        requireRelativePath(valuesPath);
        if (RestEndpoint.AUTH_DPS.equals(endpoint.auth())) {
            // The DPS client carries the service credentials: never send them to a source-chosen host.
            return dpsClient.post().uri(valuesPath);
        }
        String baseUrl = endpoint.baseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            URI absolute = UriComponentsBuilder.fromUriString(baseUrl).path(valuesPath).build(true).toUri();
            requireAllowed(absolute);
            return client.post().uri(absolute);
        }
        return client.post().uri(valuesPath);
    }

    /** An absolute URL here would replace the client's base URL, so only a plain path is accepted. */
    private static void requireRelativePath(String path) {
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("://") || path.contains("..")
                || path.contains("\\")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DATA_SOURCE_PATH_INVALID",
                    "valuesPath must be a path such as /api/data/values");
        }
    }

    private void requireAllowed(URI absolute) {
        String target = absolute.toString();
        for (String prefix : allowedBaseUrls) {
            String withSlash = prefix.endsWith("/") ? prefix : prefix + "/";
            if (target.equals(prefix) || target.startsWith(withSlash)) {
                return;
            }
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "DATA_SOURCE_HOST_NOT_ALLOWED",
                "baseUrl " + absolute.getScheme() + "://" + absolute.getAuthority()
                        + " is not in covenant.data-sources.allowed-rest-base-urls");
    }
}
