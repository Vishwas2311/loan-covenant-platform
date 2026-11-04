package com.crisil.covenant.dto;

/**
 * After a library rule changed: how many CP covenants that follow it are being checked again against
 * the new rule. The checks run in the background; each CP page shows its result as it lands. CPs with
 * their own threshold keep it and are not counted.
 */
public record RuleRecheck(int queued) {
}
