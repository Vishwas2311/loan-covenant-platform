package com.crisil.covenant.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties(ClientsProperties.class)
public class WebClientsConfig {

    @Bean
    public WebClient creditProposalWebClient(ClientsProperties props, WebClient.Builder builder, Clock clock) {
        return bankClient(props.getCreditProposal(), builder, clock);
    }

    @Bean
    public WebClient attributesWebClient(ClientsProperties props, WebClient.Builder builder, Clock clock) {
        return bankClient(props.getAttributes(), builder, clock);
    }

    @Bean
    public WebClient dataApiWebClient(ClientsProperties props, WebClient.Builder builder, Clock clock) {
        return bankClient(props.getDataApi(), builder, clock);
    }

    @Bean
    public WebClient workflowWebClient(ClientsProperties props, WebClient.Builder builder, Clock clock) {
        return bankClient(props.getWorkflow(), builder, clock);
    }

    @Bean
    public WebClient mlWebClient(ClientsProperties props, WebClient.Builder builder, Clock clock) {
        return bankClient(props.getMl(), builder, clock);
    }

    @Bean
    public WebClient notificationWebClient(ClientsProperties props, WebClient.Builder builder, Clock clock) {
        return bankClient(props.getNotification(), builder, clock);
    }

    @Bean
    public WebClient fileUploadWebClient(ClientsProperties props, WebClient.Builder builder, Clock clock) {
        return bankClient(props.getFileUpload(), builder, clock);
    }

    @Bean
    public WebClient aiSuggestionWebClient(ClientsProperties props, WebClient.Builder builder, Clock clock) {
        return bankClient(props.getAiSuggestion(), builder, clock);
    }

    /**
     * A client for one bank service, with the credentials configured for it: an OAuth client-credentials
     * token fetched and refreshed by Core (production), or a fixed bearer token (tests, short-lived setups).
     * With neither, calls go without credentials, as to the local stubs. Before this, only the DPS client
     * could authenticate (review GAP-06: the notification service got no credentials).
     */
    static WebClient bankClient(ClientsProperties.Endpoint endpoint, WebClient.Builder builder, Clock clock) {
        WebClient.Builder b = builder.clone().baseUrl(endpoint.getBaseUrl());
        if (endpoint.getOauth().isConfigured()) {
            b.filter(new ClientCredentialsTokenProvider(endpoint.getOauth(), builder, clock).bearerFilter());
        } else if (endpoint.getServiceToken() != null && !endpoint.getServiceToken().isBlank()) {
            b.defaultHeader("Authorization", "Bearer " + endpoint.getServiceToken());
        }
        return b.build();
    }

    /**
     * R1: Document Processing Service client. Production: an OAuth client-credentials token, fetched and
     * refreshed by {@link ClientCredentialsTokenProvider}. A fixed service token is still accepted (it
     * expires, so only for tests and short-lived setups). Otherwise (local dev) the DPS dev headers.
     */
    @Bean
    public WebClient dpsWebClient(ClientsProperties props, WebClient.Builder builder, Clock clock) {
        ClientsProperties.Endpoint dps = props.getDps();
        WebClient.Builder b = builder.clone().baseUrl(dps.getBaseUrl());
        if (dps.getOauth().isConfigured()) {
            b.filter(new ClientCredentialsTokenProvider(dps.getOauth(), builder, clock).bearerFilter());
        } else if (dps.getServiceToken() != null && !dps.getServiceToken().isBlank()) {
            b.defaultHeader("Authorization", "Bearer " + dps.getServiceToken());
        } else {
            b.defaultHeader("X-User", "covenant-core").defaultHeader("X-Roles", "SERVICE");
        }
        return b.build();
    }

    public static Duration defaultTimeout() {
        return Duration.ofSeconds(5);
    }

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
