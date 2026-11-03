package com.crisil.covenant.repository;

import com.crisil.covenant.entity.IngestionState;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * US-DS-13: persistence for the freshness-gate control rows. The
 * {@code (data_source_table_id, entity_key)} pair is unique, so lookups return at most one row which
 * the orchestration service upserts after each ingest-or-skip decision.
 */
@Repository
public interface IngestionStateRepository extends JpaRepository<IngestionState, String> {

    /** The single control row for one entity under a table, or empty when never considered. */
    Optional<IngestionState> findByDataSourceTableIdAndEntityKey(String dataSourceTableId, String entityKey);

    /**
     * Delete the control row for one entity under a table so the next assessment re-ingests, returning
     * the number of rows removed (0 when none existed). This is the support fix for the stale-state
     * incident: with the row gone the freshness gate falls back to {@code "missing"} and re-pulls.
     */
    long deleteByDataSourceTableIdAndEntityKey(String dataSourceTableId, String entityKey);

    /**
     * Production-support CP cleanup: delete every freshness-gate control row for one entity key (across
     * all tables). Called with the CP id for CP-grain state (always safe) and, only when the CP is the
     * obligor's sole member, with the obligor id — never when sibling CPs still share the obligor.
     */
    @Modifying
    @Query("delete from IngestionState s where s.entityKey = :entityKey")
    long deleteByEntityKey(@Param("entityKey") String entityKey);
}
