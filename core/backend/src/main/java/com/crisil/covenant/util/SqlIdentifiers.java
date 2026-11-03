package com.crisil.covenant.util;

import com.crisil.covenant.exception.ApiException;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Validates SQL identifiers (table/schema/column names) against a strict pattern before they are
 * concatenated into dynamically built SQL. Data values are always bound as parameters, never
 * passed here.
 */
public final class SqlIdentifiers {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private SqlIdentifiers() {
    }

    public static String require(String value, String what) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDENTIFIER",
                    "Illegal SQL identifier for " + what + ": " + value);
        }
        return value;
    }

    public static String optional(String value, String what) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return require(value, what);
    }
}
