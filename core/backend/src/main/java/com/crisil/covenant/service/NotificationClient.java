package com.crisil.covenant.service;

import com.crisil.covenant.dto.NotificationRequest;
import com.crisil.covenant.dto.NotificationResult;

/**
 * Sends one notification to the external notification service. Implementations must never throw — a
 * failed call is reported as a failed {@link NotificationResult} so a breach run can audit it and
 * continue with the next recipient.
 */
public interface NotificationClient {

    NotificationResult send(NotificationRequest request);
}
