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
    public WebClient creditProposalWebClient(ClientsProperties props, WebClient.Builder builder) {
        return builder
                .baseUrl(props.getCreditProposal().getBaseUrl())
                .build();
    }

    @Bean
    public WebClient attributesWebClient(ClientsProperties props, WebClient.Builder builder) {
        return builder
                .baseUrl(props.getAttributes().getBaseUrl())
                .build();
    }

    @Bean
    public WebClient dataApiWebClient(ClientsProperties props, WebClient.Builder builder) {
        return builder
                .baseUrl(props.getDataApi().getBaseUrl())
                .build();
    }

    @Bean
    public WebClient workflowWebClient(ClientsProperties props, WebClient.Builder builder) {
        return builder
                .baseUrl(props.getWorkflow().getBaseUrl())
                .build();
    }

    @Bean
    public WebClient mlWebClient(ClientsProperties props, WebClient.Builder builder) {
        return builder
                .baseUrl(props.getMl().getBaseUrl())
                .build();
    }

    @Bean
    public WebClient notificationWebClient(ClientsProperties props, WebClient.Builder builder) {
        return builder
                .baseUrl(props.getNotification().getBaseUrl())
                .build();
    }

    @Bean
    public WebClient fileUploadWebClient(ClientsProperties props, WebClient.Builder builder) {
        return builder
                .baseUrl(props.getFileUpload().getBaseUrl())
                .build();
    }

    @Bean
    public WebClient aiSuggestionWebClient(ClientsProperties props, WebClient.Builder builder) {
        return builder
                .baseUrl(props.getAiSuggestion().getBaseUrl())
                .build();
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
