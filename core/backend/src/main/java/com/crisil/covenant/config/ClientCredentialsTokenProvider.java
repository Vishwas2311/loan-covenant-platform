package com.crisil.covenant.config;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Fetches an access token with the OAuth 2.0 client-credentials grant and keeps it until shortly before
 * it expires. Entra ID access tokens last about an hour, so a fixed token in configuration would stop
 * working; this refreshes on demand. One provider is shared by every request of a client, and a refresh
 * happens once even when many requests need a token at the same moment.
 */
public class ClientCredentialsTokenProvider {

    /** Refresh this long before expiry, so a token never runs out mid-request. */
    static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

    private final ClientsProperties.ClientCredentials settings;
    private final WebClient tokenClient;
    private final Clock clock;
    private String token;
    private Instant expiresAt = Instant.EPOCH;

    public ClientCredentialsTokenProvider(ClientsProperties.ClientCredentials settings,
                                          WebClient.Builder builder, Clock clock) {
        if (blank(settings.getClientId()) || blank(settings.getClientSecret()) || blank(settings.getScope())) {
            throw new IllegalStateException("Client credentials need token-uri, client-id, client-secret and scope");
        }
        this.settings = settings;
        this.tokenClient = builder.clone().build();
        this.clock = clock;
    }

    /** A valid access token, fetched or refreshed as needed. */
    public synchronized String token() {
        if (token == null || !clock.instant().isBefore(expiresAt.minus(REFRESH_MARGIN))) {
            refresh();
        }
        return token;
    }

    /** Adds {@code Authorization: Bearer <token>} to every request, fetching the token off the event loop. */
    public ExchangeFilterFunction bearerFilter() {
        return (request, next) -> Mono.fromCallable(this::token)
                .subscribeOn(Schedulers.boundedElastic())
                .map(t -> ClientRequest.from(request).headers(h -> h.setBearerAuth(t)).build())
                .flatMap(next::exchange);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void refresh() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", settings.getClientId());
        form.add("client_secret", settings.getClientSecret());
        form.add("scope", settings.getScope());
        JsonNode body;
        try {
            body = tokenClient.post().uri(settings.getTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(WebClientsConfig.defaultTimeout());
        } catch (WebClientResponseException e) {
            // The body may echo the request; report only the status.
            throw new IllegalStateException("Token endpoint refused client credentials: HTTP " + e.getStatusCode());
        }
        if (body == null || !body.hasNonNull("access_token")) {
            throw new IllegalStateException("Token endpoint returned no access_token");
        }
        token = body.get("access_token").asText();
        long seconds = body.path("expires_in").asLong(300);
        expiresAt = clock.instant().plusSeconds(seconds);
    }
}
