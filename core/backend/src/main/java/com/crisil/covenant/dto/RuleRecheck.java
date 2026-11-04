package com.crisil.covenant.dto;

/**
 * What re-checking happened after a library rule changed: how many CP covenants were checked against
 * the new rule and how many of those came out breached or in error. CPs with their own threshold keep
 * it and are not counted.
 */
public record RuleRecheck(int checked, int breached, int errors) {
}
