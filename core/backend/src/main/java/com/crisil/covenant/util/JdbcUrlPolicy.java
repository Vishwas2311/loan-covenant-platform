package com.crisil.covenant.util;

import com.crisil.covenant.exception.ApiException;
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

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "DATA_SOURCE_JDBC_URL_INVALID", message);
    }
}
