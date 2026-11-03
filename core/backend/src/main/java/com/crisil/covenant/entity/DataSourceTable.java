package com.crisil.covenant.entity;

import com.crisil.covenant.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import com.crisil.covenant.enums.TableEntityKeyLevel;
import com.crisil.covenant.enums.TableLayout;

/**
 * A physical table under a {@link DataSource}. A data source may expose many tables; each one
 * defines its own grouping/entity-key column (e.g. {@code customer_code} / {@code CIF} /
 * {@code account_no}) and the as-of date column over which time-window aggregations are computed.
 * Measures map to a {@code (table, column)} pair.
 */
@Entity
@Table(name = "cov_data_source_table")
public class DataSourceTable extends AuditableEntity {

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "data_source_id", nullable = false)
    private DataSource dataSource;

    /** A human label for the table within the source, e.g. "Quarterly Financials". */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "schema_name", length = 128)
    private String schemaName;

    @Column(name = "table_name", nullable = false, length = 128)
    private String tableName;

    /** The grouping/entity-key column (customer code / CIF / account no). */
    @Column(name = "entity_key_column", nullable = false, length = 128)
    private String entityKeyColumn;

    /** The date column that gives the time axis for window aggregation. */
    @Column(name = "as_of_column", nullable = false, length = 128)
    private String asOfColumn;

    /**
     * Physical shape of this table. {@code COLUMNAR} (default) = one column per measure;
     * {@code KEY_VALUE} = one value column holding many measures keyed by {@link #codeColumn}.
     */
    @Column(name = "table_layout", nullable = false, length = 16)
    private TableLayout layout = TableLayout.COLUMNAR;

    /** KEY_VALUE only: the column that holds the measure identifier (e.g. {@code Ratio Code}). */
    @Column(name = "code_column", length = 128)
    private String codeColumn;

    /** KEY_VALUE only: the column that holds the measure value (e.g. {@code Ratio Value}). */
    @Column(name = "value_column", length = 128)
    private String valueColumn;

    /**
     * Identity grain of {@link #entityKeyColumn}: {@code CP_ID} (default) binds a covenant's CP_ID
     * directly; {@code OBLIGOR_ID} resolves the CP's obligor and binds that, fanning obligor data
     * out to every CP under the obligor.
     */
    @Column(name = "entity_key_level", nullable = false, length = 16)
    private TableEntityKeyLevel entityKeyLevel = TableEntityKeyLevel.CP_ID;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    public String getId() {
        return id;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getEntityKeyColumn() {
        return entityKeyColumn;
    }

    public void setEntityKeyColumn(String entityKeyColumn) {
        this.entityKeyColumn = entityKeyColumn;
    }

    public String getAsOfColumn() {
        return asOfColumn;
    }

    public void setAsOfColumn(String asOfColumn) {
        this.asOfColumn = asOfColumn;
    }

    public TableLayout getLayout() {
        return layout;
    }

    public void setLayout(TableLayout layout) {
        this.layout = layout;
    }

    public String getCodeColumn() {
        return codeColumn;
    }

    public void setCodeColumn(String codeColumn) {
        this.codeColumn = codeColumn;
    }

    public String getValueColumn() {
        return valueColumn;
    }

    public void setValueColumn(String valueColumn) {
        this.valueColumn = valueColumn;
    }

    public TableEntityKeyLevel getEntityKeyLevel() {
        return entityKeyLevel;
    }

    public void setEntityKeyLevel(TableEntityKeyLevel entityKeyLevel) {
        this.entityKeyLevel = entityKeyLevel;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
