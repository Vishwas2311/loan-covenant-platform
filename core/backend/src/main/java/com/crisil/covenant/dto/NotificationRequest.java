package com.crisil.covenant.dto;

import java.util.List;
import java.util.Map;

/**
 * Body posted to the external notification service. Serialised verbatim to the contract the service
 * expects:
 * <pre>
 * {
 *   "moduleCode": "COV",
 *   "templateCode": "Cov_Template",
 *   "emailTo": "rm@bank.com",
 *   "emailCC": "wrm@bank.com",
 *   "variables": { "cpMonitoring": [ { "CP_ID": "CP-1001", "Applied": "10", ... } ] },
 *   "priority": "HIGH"
 * }
 * </pre>
 * Each {@code cpMonitoring} entry is a {@link java.util.LinkedHashMap} so its key order and exact key
 * names (owned by {@code BreachNotificationService}) survive serialisation.
 */
public record NotificationRequest(
        String moduleCode,
        String templateCode,
        String emailTo,
        String emailCC,
        Variables variables,
        String priority) {

    public record Variables(List<Map<String, String>> cpMonitoring) {
    }
}
