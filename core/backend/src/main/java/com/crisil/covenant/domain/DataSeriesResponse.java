package com.crisil.covenant.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

/**
 * WS3/US-DS-12: the response of {@code DataApiClient.fetchValues} — the single as-of-aware contract
 * serving both reads. A point-in-time request ({@code from == null}) returns one latest
 * {@link DataPoint} {@code <= to}; a windowed request returns every dated point over the range, so a
 * multi-year windowed aggregation can be satisfied from a single ingest rather than accruing one
 * snapshot per assessment.
 *
 * <p>{@code sourceChangedAt} is the upstream's own "data last changed" timestamp when it reports one
 * (nullable); the freshness gate uses it to re-ingest when the source changes between pulls. Unknown
 * JSON fields are ignored so the upstream contract can grow without breaking deserialisation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DataSeriesResponse(String cpId, List<DataPoint> points, List<String> missingKeys,
                                 Instant sourceChangedAt) {

    public List<DataPoint> safePoints() {
        return points == null ? List.of() : points;
    }

    public List<String> safeMissingKeys() {
        return missingKeys == null ? List.of() : missingKeys;
    }
}
