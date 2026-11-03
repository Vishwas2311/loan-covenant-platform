package com.crisil.covenant.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * US-033: in the {@code dev} profile, auto-start a WireMock server on the configured port and
 * register stub mappings shipped under {@code classpath:wiremock/mappings/}. Lets the app run
 * end-to-end without the external CP / Attributes / Data / Workflow services.
 */
@Configuration
@Profile("dev")
public class WireMockDevConfig {

    private static final Logger LOG = LoggerFactory.getLogger(WireMockDevConfig.class);

    private WireMockServer server;

    @Bean
    public WireMockServer wireMockServer(@Value("${covenant.wiremock.port:9311}") int port) {
        this.server = new WireMockServer(WireMockConfiguration.options().port(port));
        this.server.start();
        loadStubMappings();
        LOG.info("WireMock dev server started on port {} with {} mappings",
                this.server.port(), this.server.getStubMappings().size());
        return this.server;
    }

    private void loadStubMappings() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath:wiremock/mappings/*.json");
            for (Resource resource : resources) {
                registerOne(resource);
            }
        } catch (IOException ex) {
            LOG.warn("Could not scan wiremock/mappings/: {}", ex.getMessage());
        }
    }

    private void registerOne(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            StubMapping mapping = StubMapping.buildFrom(json);
            server.addStubMapping(mapping);
        } catch (IOException | RuntimeException ex) {
            LOG.warn("Skipping invalid stub {}: {}", resource.getFilename(), ex.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
    }
}
