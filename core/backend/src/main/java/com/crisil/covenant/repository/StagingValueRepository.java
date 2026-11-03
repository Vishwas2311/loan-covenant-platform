package com.crisil.covenant.repository;

import com.crisil.covenant.entity.StagingValue;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the {@link StagingValue} staging store. Writes come from ingestion
 * (delete-then-insert scoped to the as-of dates just read); the resolver reads staging rows via a
 * direct {@code JdbcTemplate} query to preserve its windowing SQL.
 */
@Repository
public interface StagingValueRepository extends JpaRepository<StagingValue, String> {

    /**
     * Idempotency anchor for JIT ingestion: clears any prior copy for one entity across exactly the
     * as-of dates being re-ingested, so a fresh read overwrites those dates without duplicating and
     * without discarding dates outside this read (e.g. earlier REST snapshots that still stand).
     *
     * <p><b>Must stay a bulk JPQL delete.</b> As a derived {@code deleteBy…} method Spring Data would
     * select the rows and {@code em.remove()} each one, which merely <em>queues</em> the deletes — and
     * Hibernate's flush executes queued inserts <em>before</em> queued deletes. Ingestion's very next
     * step inserts the same natural keys, so the DELETE would land after the INSERT and every re-ingest
     * of an already-staged date would fail on {@code uk_staging_value}. A bulk delete issues its
     * statement immediately, restoring the delete-then-insert order the ingest relies on.
     * {@code flushAutomatically} keeps that guarantee independent of Hibernate's auto-flush heuristics.
     * {@code clearAutomatically} is deliberately left off: clearing the whole persistence context here
     * would detach the caller's in-flight entities (the assessment's {@code DataSourceTable} loop, the
     * gate's {@code IngestionState}) and trade this bug for lazy-initialisation failures.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from StagingValue s where s.dataSourceTable.id = :dataSourceTableId "
            + "and s.entityKey = :entityKey and s.asOfDate in :asOfDates")
    long deleteByDataSourceTableIdAndEntityKeyAndAsOfDateIn(
            @Param("dataSourceTableId") String dataSourceTableId,
            @Param("entityKey") String entityKey,
            @Param("asOfDates") Collection<LocalDate> asOfDates);

    /** All staged rows for one entity under a table (used by tests and troubleshooting). */
    List<StagingValue> findByDataSourceTableIdAndEntityKey(String dataSourceTableId, String entityKey);

    /**
     * How many rows are staged for one entity under a table, across every item code. Production-support
     * diagnostics uses this to tell "nothing staged for the entity at all" (the freshness-gate bug when
     * a control row still claims fresh) apart from "entity has rows, just not for this measure's item
     * code" (a measure→source mapping problem).
     */
    long countByDataSourceTableIdAndEntityKey(String dataSourceTableId, String entityKey);

    /**
     * The staged rows for one entity under a table for a single item code. Diagnostics derives the
     * per-term staging count, min/max as-of and in-window count from this list so it mirrors exactly
     * what the resolver would read for the term.
     */
    List<StagingValue> findByDataSourceTableIdAndEntityKeyAndItemCode(
            String dataSourceTableId, String entityKey, String itemCode);

    /**
     * US-DS-12 incremental cursor: the latest as-of date already staged for one entity under a table,
     * or empty when nothing has been ingested yet. REST series ingestion fetches only dates newer than
     * this watermark (full window only on the first run).
     */
    @Query("SELECT MAX(s.asOfDate) FROM StagingValue s "
            + "WHERE s.dataSourceTable.id = :tableId AND s.entityKey = :entityKey")
    Optional<LocalDate> findMaxAsOfDate(@Param("tableId") String tableId,
                                        @Param("entityKey") String entityKey);

    /**
     * Production-support health: how many rows are staged across every table of one data source. Feeds
     * the per-source health report so a support engineer can distinguish a source that is reachable yet
     * has never landed any data from one that is simply unreachable.
     */
    @Query("SELECT COUNT(s) FROM StagingValue s WHERE s.dataSourceTable.dataSource.id = :dataSourceId")
    long countByDataSourceId(@Param("dataSourceId") String dataSourceId);

    /**
     * Production-support health: the most recent ingest instant across every table of one data source,
     * or empty when nothing has ever been staged for it.
     */
    @Query("SELECT MAX(s.ingestedAt) FROM StagingValue s "
            + "WHERE s.dataSourceTable.dataSource.id = :dataSourceId")
    Optional<Instant> findMaxIngestedAtByDataSourceId(@Param("dataSourceId") String dataSourceId);

    /**
     * Production-support CP cleanup: delete every staged row for one entity key (across all tables) in a
     * single bulk statement. Called with the CP id for CP-grain data (always safe) and, only when the CP
     * is the obligor's sole member, with the obligor id — never when sibling CPs still share the obligor.
     */
    @Modifying
    @Query("delete from StagingValue s where s.entityKey = :entityKey")
    long deleteByEntityKey(@Param("entityKey") String entityKey);
     /**
     * The earliest as-of date already staged for one entity under a table, or empty when nothing has
     * been ingested yet. The reach-back guard uses this: when a covenant's requested window start is
     * earlier than everything staged, ingestion does one full {@code [from, asOf]} refetch instead of a
     * forward-only incremental pull, so a widened look-back is never silently under-fetched.
     */
    @Query("SELECT MIN(s.asOfDate) FROM StagingValue s "
            + "WHERE s.dataSourceTable.id = :tableId AND s.entityKey = :entityKey")
    Optional<LocalDate> findMinAsOfDate(@Param("tableId") String tableId,
                                        @Param("entityKey") String entityKey);
}
