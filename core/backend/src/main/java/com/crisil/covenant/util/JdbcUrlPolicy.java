package com.crisil.covenant.util;

import com.crisil.covenant.exception.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * Which JDBC URLs an MSSQL data source may use. Data sources are edited through the API, and some
 * JDBC drivers run code from the URL: H2's {@code INIT=RUNSCRIPT FROM 'http://...'} executes any SQL,
 * including Java aliases. Only SQL Server URLs are accepted, plus H2 (without INIT) where a profile
 * allows it for local demos and tests.
 */
public final class JdbcUrlPolicy {

    private static final String SQLSERVER = "jdbc:sqlserver://";
    private static final String H2 = "jdbc:h2:";

    private JdbcUrlPolicy() {
    }

    /** Checked before every connection, whatever the profile. */
    public static void requireSafe(String url) {
        String lower = url.trim().toLowerCase(Locale.ROOT);
        boolean h2 = lower.startsWith(H2);
        if (!lower.startsWith(SQLSERVER) && !h2) {
            throw invalid("Only SQL Server JDBC URLs (jdbc:sqlserver://...) are supported");
        }
        if (h2 && (lower.contains("init=") || lower.contains("runscript"))) {
            throw invalid("H2 URLs may not run INIT scripts");
        }
    }

    /** Checked when a data source is saved: H2 only where {@code allowH2} (local demos and tests). */
    public static void requireAllowed(String url, boolean allowH2) {
        requireSafe(url);
        if (!allowH2 && url.trim().toLowerCase(Locale.ROOT).startsWith(H2)) {
            throw invalid("H2 data sources are not allowed in this environment");
        }
    }

    /**
     * SQL Server URLs may only name allowlisted hosts ({@code covenant.data-sources.allowed-jdbc-hosts}):
     * otherwise anyone who edits data sources could make Core open connections to any host it can reach.
     * Entries are host names, compared without port or instance; {@code *} allows any host. The host part
     * and the {@code serverName} and {@code failoverPartner} properties are all checked, since each can
     * name the server. H2 URLs name no host and are governed by {@link #requireAllowed}.
     */
    public static void requireAllowedHost(String url, List<String> allowedHosts) {
        String lower = url.trim().toLowerCase(Locale.ROOT);
        List<String> allowed = allowedHosts.stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        if (!lower.startsWith(SQLSERVER) || allowed.contains("*")) {
            return;
        }
        List<String> hosts = sqlServerHosts(lower);
        if (hosts.isEmpty()) {
            throw invalid("The JDBC URL names no server");
        }
        for (String host : hosts) {
            if (!allowed.contains(host)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DATA_SOURCE_HOST_NOT_ALLOWED",
                        "Server " + host + " is not in covenant.data-sources.allowed-jdbc-hosts");
            }
        }
    }

    /** Every host a SQL Server URL can connect to, lower-case, without port or instance name. */
    static List<String> sqlServerHosts(String lowerUrl) {
        String rest = lowerUrl.substring(SQLSERVER.length());
        int semicolon = rest.indexOf(';');
        List<String> hosts = new ArrayList<>();
        addHost(hosts, semicolon < 0 ? rest : rest.substring(0, semicolon));
        if (semicolon >= 0) {
            for (String property : rest.substring(semicolon + 1).split(";")) {
                int eq = property.indexOf('=');
                String key = eq < 0 ? property.trim() : property.substring(0, eq).trim();
                if (eq > 0 && (key.equals("servername") || key.equals("server") || key.equals("failoverpartner"))) {
                    addHost(hosts, property.substring(eq + 1));
                }
            }
        }
        return hosts;
    }

    private static void addHost(List<String> hosts, String hostPart) {
        String host = hostPart.trim();
        int cut = host.length();
        for (char c : new char[] {':', '\\', '/'}) {
            int at = host.indexOf(c);
            if (at >= 0) {
                cut = Math.min(cut, at);
            }
        }
        host = host.substring(0, cut).trim();
        if (!host.isEmpty()) {
            hosts.add(host);
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "DATA_SOURCE_JDBC_URL_INVALID", message);
    }
}
