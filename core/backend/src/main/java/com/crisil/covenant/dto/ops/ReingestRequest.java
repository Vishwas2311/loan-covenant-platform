package com.crisil.covenant.dto.ops;

/**
 * Optional body for the force-re-ingest endpoint. When {@code tableId} is {@code null}/blank every
 * distinct source table the CP's covenants use is re-ingested; otherwise the action is scoped to that
 * one table. Coverage-excluded ({@code dto/**}).
 *
 * @param tableId a single data-source-table id to scope the re-ingest to, or {@code null} for all
 */
public record ReingestRequest(String tableId) {
}
