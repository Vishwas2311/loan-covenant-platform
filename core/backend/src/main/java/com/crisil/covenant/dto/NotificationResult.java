package com.crisil.covenant.dto;

/**
 * Internal outcome of one notification-service call — never sent over the wire. A 2xx response is a
 * {@code success}; any non-2xx, timeout, connection error or open circuit yields a failed result
 * (the client never throws) so the batch run can record it and carry on.
 */
public record NotificationResult(boolean success, Integer httpStatus, String responseBody, String error) {
}
