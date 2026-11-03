package com.crisil.covenant.enums;

/**
 * Why a single formula term would (or would not) resolve to a value for a CP — the classification the
 * production-support diagnostics attaches to each term so an engineer can see, without opening the
 * database, exactly where the pipeline breaks. Mirrors the read path of {@code
 * MeasureAggregationResolver} / {@code AssessmentService}: a term that is anything other than {@link
 * #OK} is a term the assessment would report under {@code "Missing values for measures: [...]"}.
 */
public enum DiagnosticReason {
    /** The term resolves: its measure is registered, the entity key is known, and staging holds data
     *  for the item code within the covenant's window. */
    OK,
    /** The term's table is obligor-keyed but the CP has no {@code cov_obligor_cp_map} row, so no
     *  entity key can be bound — the resolver returns {@code present=false}. */
    NO_OBLIGOR_MAPPING,
    /** The formula references a measure code that has no {@code cov_measure} row. */
    MEASURE_NOT_REGISTERED,
    /** Staging holds nothing for the entity under this table, and the freshness gate would still
     *  <em>skip</em> re-ingest (it believes the copy is fresh) — the stale-ingestion-state bug. Clearing
     *  {@code cov_ingestion_state} for the entity lets the next assessment re-ingest. */
    STAGING_EMPTY_INGEST_STATE_STALE,
    /** Staging holds nothing for the entity under this table, but not because of a stale gate: either no
     *  control row exists yet, or the gate would re-ingest on the next run (it will self-heal). */
    STAGING_EMPTY_NO_STATE,
    /** The entity has staged rows under this table, but none for this measure's item code — a
     *  measure→source mapping problem ({@code source_column} / {@code source_code}). */
    ITEM_CODE_NOT_STAGED,
    /** Staged rows exist for the item code, but none fall inside the covenant's evaluation window. */
    WINDOW_EMPTY
}
