package com.crisil.covenant.dto;

/** C9: how many checks in one month ended compliant, breached or in error, across the user's CPs. */
public record ResultTrendPoint(String month, long compliant, long breached, long error, long total) {
}
