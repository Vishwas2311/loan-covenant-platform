package com.crisil.covenant.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * The port of the separate management server ({@code management.server.port}), once it is bound. Requests
 * that arrive on it may read the actuator without a token: that port is internal (monitoring scrapes it,
 * the load balancer never routes to it), while the public port serves only {@code /livez} and
 * {@code /readyz}. Without a separate management port nothing matches, and the actuator needs a token.
 */
@Component
public class ManagementPort implements ApplicationListener<WebServerInitializedEvent> {

    private volatile int port = -1;

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if ("management".equals(event.getApplicationContext().getServerNamespace())) {
            port = event.getWebServer().getPort();
        }
    }

    /** True for an actuator request received on the management port. */
    public boolean matches(HttpServletRequest request) {
        return port > 0 && request.getLocalPort() == port && request.getRequestURI().startsWith("/actuator");
    }
}
