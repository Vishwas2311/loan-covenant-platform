package com.crisil.covenant.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import com.crisil.covenant.config.CurrentUserContext;

/**
 * JWT mode with a separate management port: the actuator answers there without a token, the application
 * port serves only the health probes publicly, and the API still needs a token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "covenant.security.mode=JWT",
    "covenant.security.jwt.issuer-uri=https://issuer.test/v2.0",
    "covenant.security.jwt.audience=api://covenant-core",
    "covenant.security.jwt.jwk-set-uri=https://issuer.test/keys", // fetched lazily: no token, no network
    "management.server.port=0"
})
@ActiveProfiles("test")
@AutoConfigureObservability // tests switch metrics exporters off by default
class ManagementPortSecurityIT {

    @LocalServerPort int appPort;
    @LocalManagementPort int managementPort;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterAll
    static void restoreFallback() {
        CurrentUserContext.setHeaderFallbackEnabled(true);
    }

    private int status(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void actuatorIsOpenOnlyOnTheManagementPort() throws Exception {
        assertThat(managementPort).isNotEqualTo(appPort);
        assertThat(status(managementPort, "/actuator/prometheus")).isEqualTo(200);
        assertThat(status(managementPort, "/actuator/metrics")).isEqualTo(200);

        assertThat(status(appPort, "/readyz")).isEqualTo(200);
        assertThat(status(appPort, "/livez")).isEqualTo(200);
        assertThat(status(appPort, "/actuator/prometheus")).isIn(401, 404);
        assertThat(status(appPort, "/api/v1/covenants")).isEqualTo(401);
    }
}
