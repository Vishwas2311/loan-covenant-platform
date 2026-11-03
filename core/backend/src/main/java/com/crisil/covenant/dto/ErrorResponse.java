package com.crisil.covenant.dto;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(
        String code,
        String message,
        List<FieldError> details,
        Instant timestamp) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, List.of(), Instant.now());
    }

    public static ErrorResponse of(String code, String message, List<FieldError> details) {
        return new ErrorResponse(code, message, details, Instant.now());
    }

    public record FieldError(String field, String message) {}
}
