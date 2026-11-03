package com.crisil.covenant.service.impl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.covenant.dto.FileDownloadResult;
import com.crisil.covenant.dto.FileUploadResult;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.netty.channel.ChannelOption;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

/**
 * US-MON-17: the resilient file-upload client turns any failure into a failed
 * {@link FileUploadResult} (never throws) so an upload error is surfaced without corrupting data.
 */
class WebClientFileUploadClientTest {

    private WireMockServer wireMock;
    private LoopResources loopResources;
    private WebClientFileUploadClient client;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        loopResources = LoopResources.create("fileupload-test", 1, true);
        HttpClient httpClient = HttpClient.create(ConnectionProvider.newConnection())
                .runOn(loopResources)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                .responseTimeout(Duration.ofSeconds(2));
        WebClient web = WebClient.builder()
                .baseUrl("http://127.0.0.1:" + wireMock.port())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        TimeLimiterRegistry tlr = TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(8))
                .cancelRunningFuture(true)
                .build());
        RetryRegistry rr = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(20))
                .build());
        CircuitBreakerRegistry cbr = CircuitBreakerRegistry.ofDefaults();
        client = new WebClientFileUploadClient(web, cbr, rr, tlr);
    }

    @AfterEach
    void stop() {
        wireMock.stop();
        loopResources.disposeLater().block(Duration.ofSeconds(5));
    }

    private FileUploadResult upload() {
        return client.upload("evidence.pdf", "application/pdf",
                "hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void returnsFileIdOn2xx() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/files"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"fileId\":\"file-42\",\"fileName\":\"stored.pdf\"}")));

        FileUploadResult result = upload();

        assertThat(result.success()).isTrue();
        assertThat(result.fileId()).isEqualTo("file-42");
        assertThat(result.fileName()).isEqualTo("stored.pdf");
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.error()).isNull();
    }

    @Test
    void fallsBackToRequestFileNameWhenResponseOmitsIt() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/files"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"fileId\":\"file-7\"}")));

        FileUploadResult result = upload();

        assertThat(result.success()).isTrue();
        assertThat(result.fileName()).isEqualTo("evidence.pdf");
    }

    @Test
    void twoXxWithoutFileIdIsNotSuccess() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/files"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        FileUploadResult result = upload();

        assertThat(result.success()).isFalse();
        assertThat(result.fileId()).isNull();
    }

    @Test
    void returnsFailedResultOn5xxWithoutThrowing() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/files"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        FileUploadResult result = upload();

        assertThat(result.success()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(500);
        assertThat(result.error()).isNotNull();
    }

    @Test
    void returnsFailedResultOnTimeout() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/files"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(4000)
                        .withBody("{\"fileId\":\"file-1\"}")));

        assertThat(upload().success()).isFalse();
    }

    @Test
    void returnsFailedResultOnConnectionError() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/files"))
                .willReturn(aResponse().withFault(
                        com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        assertThat(upload().success()).isFalse();
    }

    @Test
    void nullContentTypeAndBytesStillUpload() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/files"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"fileId\":\"file-9\",\"fileName\":\"x\"}")));

        FileUploadResult result = client.upload("weird name", null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.fileId()).isEqualTo("file-9");
    }

    // ---- US-MON-23 download (a read: may throw a gateway error, unlike upload) ----

    @Test
    void downloadReturnsBytesContentTypeAndFilenameOn2xx() {
        wireMock.stubFor(get(urlEqualTo("/api/v1/files/file-42"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/pdf")
                        .withHeader("Content-Disposition", "attachment; filename=\"server.pdf\"")
                        .withBody("PDFDATA")));

        FileDownloadResult result = client.download("file-42");

        assertThat(result.content()).isEqualTo("PDFDATA".getBytes(StandardCharsets.UTF_8));
        assertThat(result.contentType()).isEqualTo("application/pdf");
        assertThat(result.fileName()).isEqualTo("server.pdf");
    }

    @Test
    void downloadFallsBackToOctetStreamAndFileIdWhenHeadersAbsent() {
        wireMock.stubFor(get(urlEqualTo("/api/v1/files/file-7"))
                .willReturn(aResponse().withStatus(200).withBody("RAW")));

        FileDownloadResult result = client.download("file-7");

        assertThat(result.contentType()).isEqualTo("application/octet-stream");
        assertThat(result.fileName()).isEqualTo("file-7"); // no Content-Disposition ⇒ fall back to id
    }

    @Test
    void downloadThrowsBadGatewayOn5xx() {
        wireMock.stubFor(get(urlEqualTo("/api/v1/files/file-x"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        assertThatThrownBy(() -> client.download("file-x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(502));
    }

    @Test
    void downloadThrowsBadGatewayOnConnectionError() {
        wireMock.stubFor(get(urlEqualTo("/api/v1/files/file-c"))
                .willReturn(aResponse().withFault(
                        com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> client.download("file-c"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
