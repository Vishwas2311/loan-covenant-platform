package com.crisil.covenant.dto;

/**
 * One CP's position in its renewal lineage (US-MON-23): the oldest (root) CP is {@code cycleNumber}
 * 1, each child increments, and the opened CP is the highest cycle with {@code current == true}.
 * Read model only — coverage-excluded ({@code dto/**}).
 */
public record CpCycleView(
        int cycleNumber,
        String cpId,
        boolean current) {
}
