package com.crisil.covenant.entity;

import com.crisil.covenant.enums.TableEntityKeyLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.UuidGenerator;

/**
 * One C5 "new data for an entity" event received from the Document Processing Service. Append-only;
 * the unique key (data source, entity, changedAt) makes a re-delivered event a no-op.
 */
@Entity
@Table(name = "cov_data_event")
public class DataEvent {

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "data_source_id", nullable = false, length = 36)
    private String dataSourceId;

    @Column(name = "entity_key", nullable = false, length = 128)
    private String entityKey;

    @Column(name = "entity_key_level", nullable = false, length = 16)
    private TableEntityKeyLevel entityKeyLevel;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(name = "document_id", length = 64)
    private String documentId;

    @Column(name = "received_by", nullable = false, length = 50)
    private String receivedBy;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "assessments_queued", nullable = false)
    private int assessmentsQueued;

    protected DataEvent() {
    }

    public DataEvent(String dataSourceId, String entityKey, TableEntityKeyLevel entityKeyLevel, LocalDate asOfDate,
                     Instant changedAt, String documentId, String receivedBy, Instant receivedAt,
                     int assessmentsQueued) {
        this.dataSourceId = dataSourceId;
        this.entityKey = entityKey;
        this.entityKeyLevel = entityKeyLevel;
        this.asOfDate = asOfDate;
        this.changedAt = changedAt;
        this.documentId = documentId;
        this.receivedBy = receivedBy;
        this.receivedAt = receivedAt;
        this.assessmentsQueued = assessmentsQueued;
    }

    public String getId() {
        return id;
    }

    public String getDataSourceId() {
        return dataSourceId;
    }

    public String getEntityKey() {
        return entityKey;
    }

    public TableEntityKeyLevel getEntityKeyLevel() {
        return entityKeyLevel;
    }

    public LocalDate getAsOfDate() {
        return asOfDate;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getReceivedBy() {
        return receivedBy;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public int getAssessmentsQueued() {
        return assessmentsQueued;
    }
}
