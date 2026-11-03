package com.crisil.covenant.entity;

import com.crisil.covenant.enums.DataColumnType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.UuidGenerator;

/**
 * A durable, persisted copy of one datum read from a {@link DataSourceTable}. The staging store is a
 * generic long/EAV table: one row per {@code (table, entity_key, as_of_date, item_code)}. Every
 * registered source — MSSQL or REST — is ingested here before an assessment runs, so covenants
 * always evaluate against a stored copy rather than the live source.
 *
 * <p>Exactly one of the typed value columns ({@link #numValue}/{@link #strValue}/{@link #dateValue}/
 * {@link #boolValue}) is populated per row, selected by {@link #dataType}. {@link #itemCode} is the
 * measure's {@code sourceColumn} for a COLUMNAR table or its {@code sourceCode} for a KEY_VALUE table
 * — collapsing both layouts to a single lookup predicate.
 */
@Entity
@Table(name = "cov_staging_value")
public class StagingValue extends AuditableEntity {

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "data_source_table_id", nullable = false)
    private DataSourceTable dataSourceTable;

    /** CP_ID or obligor id, per the table's {@code entity_key_column}. */
    @Column(name = "entity_key", nullable = false, length = 128)
    private String entityKey;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    /** COLUMNAR: {@code measure.sourceColumn}; KEY_VALUE: {@code measure.sourceCode}. */
    @Column(name = "item_code", nullable = false, length = 128)
    private String itemCode;

    @Column(name = "num_value", precision = 38, scale = 6)
    private BigDecimal numValue;

    @Column(name = "str_value", length = 2000)
    private String strValue;

    @Column(name = "date_value")
    private LocalDate dateValue;

    @Column(name = "bool_value")
    private Boolean boolValue;

    @Column(name = "data_type", nullable = false, length = 16)
    private DataColumnType dataType = DataColumnType.NUMBER;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    /** Correlates all rows written by a single ingestion pass (for audit/troubleshooting). */
    @Column(name = "ingest_batch_id", nullable = false, length = 36)
    private String ingestBatchId;

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

    public LocalDate getAsOfDate() {
        return asOfDate;
    }

    public void setAsOfDate(LocalDate asOfDate) {
        this.asOfDate = asOfDate;
    }

    public String getItemCode() {
        return itemCode;
    }

    public void setItemCode(String itemCode) {
        this.itemCode = itemCode;
    }

    public BigDecimal getNumValue() {
        return numValue;
    }

    public void setNumValue(BigDecimal numValue) {
        this.numValue = numValue;
    }

    public String getStrValue() {
        return strValue;
    }

    public void setStrValue(String strValue) {
        this.strValue = strValue;
    }

    public LocalDate getDateValue() {
        return dateValue;
    }

    public void setDateValue(LocalDate dateValue) {
        this.dateValue = dateValue;
    }

    public Boolean getBoolValue() {
        return boolValue;
    }

    public void setBoolValue(Boolean boolValue) {
        this.boolValue = boolValue;
    }

    public DataColumnType getDataType() {
        return dataType;
    }

    public void setDataType(DataColumnType dataType) {
        this.dataType = dataType;
    }

    public Instant getIngestedAt() {
        return ingestedAt;
    }

    public void setIngestedAt(Instant ingestedAt) {
        this.ingestedAt = ingestedAt;
    }

    public String getIngestBatchId() {
        return ingestBatchId;
    }

    public void setIngestBatchId(String ingestBatchId) {
        this.ingestBatchId = ingestBatchId;
    }
}
