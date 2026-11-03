package com.crisil.covenant.entity;

import com.crisil.covenant.enums.IngestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.UuidGenerator;

/**
 * US-DS-13: the freshness-gate control row for one {@code (data_source_table, entity_key)} pair. It
 * caches just enough to make the ingest-or-skip decision cheap and auditable without scanning
 * {@code cov_staging_value} on every candidate:
 * <ul>
 *   <li>{@link #stagedAsOf} — the watermark (max staged as-of date for the entity);</li>
 *   <li>{@link #stagedIngestedAt} — when that copy was written (vs {@link #sourceChangedAt});</li>
 *   <li>{@link #sourceChangedAt} — when the source last published new/revised data, or a
 *       force-refresh stamp; {@code null} when the source cannot report change;</li>
 *   <li>{@link #nextEligibleAt} — a TTL floor for sources with no change signal;</li>
 *   <li>{@link #lastStatus} — the outcome of the most recent decision.</li>
 * </ul>
 */
@Entity
@Table(name = "cov_ingestion_state")
public class IngestionState extends AuditableEntity {

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "data_source_table_id", nullable = false)
    private DataSourceTable dataSourceTable;

    @Column(name = "entity_key", nullable = false, length = 128)
    private String entityKey;

    @Column(name = "staged_as_of")
    private LocalDate stagedAsOf;

    @Column(name = "staged_ingested_at")
    private Instant stagedIngestedAt;

    @Column(name = "source_changed_at")
    private Instant sourceChangedAt;

    @Column(name = "next_eligible_at")
    private Instant nextEligibleAt;

    @Column(name = "last_status", nullable = false, length = 16)
    private IngestStatus lastStatus = IngestStatus.SKIPPED;

    public String getId() {
        return id;
    }

    public DataSourceTable getDataSourceTable() {
        return dataSourceTable;
    }

    public void setDataSourceTable(DataSourceTable dataSourceTable) {
        this.dataSourceTable = dataSourceTable;
    }

    public String getEntityKey() {
        return entityKey;
    }

    public void setEntityKey(String entityKey) {
        this.entityKey = entityKey;
    }

    public LocalDate getStagedAsOf() {
        return stagedAsOf;
    }

    public void setStagedAsOf(LocalDate stagedAsOf) {
        this.stagedAsOf = stagedAsOf;
    }

    public Instant getStagedIngestedAt() {
        return stagedIngestedAt;
    }

    public void setStagedIngestedAt(Instant stagedIngestedAt) {
        this.stagedIngestedAt = stagedIngestedAt;
    }

    public Instant getSourceChangedAt() {
        return sourceChangedAt;
    }

    public void setSourceChangedAt(Instant sourceChangedAt) {
        this.sourceChangedAt = sourceChangedAt;
    }

    public Instant getNextEligibleAt() {
        return nextEligibleAt;
    }

    public void setNextEligibleAt(Instant nextEligibleAt) {
        this.nextEligibleAt = nextEligibleAt;
    }

    public IngestStatus getLastStatus() {
        return lastStatus;
    }

    public void setLastStatus(IngestStatus lastStatus) {
        this.lastStatus = lastStatus;
    }
}
