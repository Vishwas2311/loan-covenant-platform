package com.crisil.covenant.util;

import com.crisil.covenant.entity.DataSource;
import com.crisil.covenant.exception.ApiException;
import java.util.function.UnaryOperator;
import org.springframework.http.HttpStatus;

/**
 * Resolves the password of an MSSQL {@link DataSource}. The preferred form is {@code secretRef}: the
 * name of an environment variable (filled by the platform's secret store) that holds the password, so
 * nothing secret is stored in {@code connection_config_json}. A plaintext {@code password} is still
 * read for local use; production rejects it on save (see {@code DataSourceService}).
 *
 * <p>Only variables starting with {@value #PREFIX} can be referenced. Without that limit an admin
 * could point a source at their own JDBC URL with {@code secretRef=MSSQL_SA_PASSWORD} and have Core
 * send any of its secrets there.
 */
public final class DataSourceSecrets {

    public static final String PREFIX = "COVENANT_DS_";

    private static UnaryOperator<String> environment = System::getenv;

    private DataSourceSecrets() {
    }

    /** The password to connect with, or {@code null} when the source has none. */
    public static String password(DataSource source) {
        String ref = source.config("secretRef");
        if (ref == null || ref.isBlank()) {
            return source.config("password");
        }
        validateRef(ref);
        String value = environment.apply(ref);
        if (value == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DATA_SOURCE_SECRET_MISSING",
                    "Data source '" + source.getName() + "' refers to secret " + ref
                            + ", which is not set in Core's environment");
        }
        return value;
    }

    /** Rejects a {@code secretRef} outside the allowed variable names. */
    public static void validateRef(String ref) {
        if (!ref.startsWith(PREFIX) || !ref.matches("[A-Z0-9_]+")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DATA_SOURCE_SECRET_REF_INVALID",
                    "secretRef must be an environment variable name starting with " + PREFIX);
        }
    }

    /** Test hook: replaces the environment lookup; pass {@code null} to restore {@link System#getenv}. */
    static void useEnvironment(UnaryOperator<String> lookup) {
        environment = lookup == null ? System::getenv : lookup;
    }
}
