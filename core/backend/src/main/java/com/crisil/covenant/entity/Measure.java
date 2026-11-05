package com.crisil.covenant.entity;

import com.crisil.covenant.entity.AuditableEntity;
import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.formula.FunctionKind;
import com.crisil.covenant.service.MeasureChangeListener;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.NamedSubgraph;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

/**
 * A first-class, reusable measure (DSCR, PAT, EBITDA, ICR …) referenced by name in covenant
 * formulas. A measure is backed by a single column on a {@link DataSourceTable}; formulas may wrap
 * it in an aggregation over a time window. The {@code code} is the formula-facing identifier,
 * decoupled from the physical {@code sourceColumn}.
 */
@Entity
// Named so @EntityGraph finds it directly: with attributePaths alone, Spring Data first looks up a named
// graph, gets an IllegalArgumentException on every call, then builds the graph (load test: ~10% of CPU).
@NamedEntityGraph(name = Measure.WITH_TABLE_AND_SOURCE,
        attributeNodes = @NamedAttributeNode(value = "dataSourceTable", subgraph = "table"),
        subgraphs = @NamedSubgraph(name = "table", attributeNodes = @NamedAttributeNode("dataSource")))
@Table(name = "cov_measure")
@EntityListeners(MeasureChangeListener.class)
public class Measure extends AuditableEntity {

    public static final String WITH_TABLE_AND_SOURCE = "Measure.withTableAndSource";

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "data_source_table_id", nullable = false)
    private DataSourceTable dataSourceTable;

    /**
     * COLUMNAR tables only: the physical column this measure reads. Nullable because KEY_VALUE
     * measures use {@link #sourceCode} instead.
     */
    @Column(name = "source_column", length = 128)
    private String sourceColumn;

    /**
     * KEY_VALUE tables only: the code value (matched against the table's code column) that
     * identifies this measure's rows, e.g. {@code DSCR}. Null for COLUMNAR measures.
     */
    @Column(name = "source_code", length = 128)
    private String sourceCode;

    @Column(name = "default_aggregation", nullable = false, length = 16)
    private FunctionKind defaultAggregation = FunctionKind.LATEST;

    @Column(name = "data_type", nullable = false, length = 16)
    private DataColumnType dataType = DataColumnType.NUMBER;

    @Column(name = "unit", length = 32)
    private String unit;

    /** T9: a point-in-time value older than this many days before the as-of date counts as missing. */
    @Column(name = "max_age_days")
    private Integer maxAgeDays;

    public Integer getMaxAgeDays() {
        return maxAgeDays;
    }

    public void setMaxAgeDays(Integer maxAgeDays) {
        this.maxAgeDays = maxAgeDays;
    }

    @Column(name = "active", nullable = false)
    private boolean active = true;

    public String getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public DataSourceTable getDataSourceTable() {
        return dataSourceTable;
    }

    public void setDataSourceTable(DataSourceTable dataSourceTable) {
        this.dataSourceTable = dataSourceTable;
    }

    public String getSourceColumn() {
        return sourceColumn;
    }

    public void setSourceColumn(String sourceColumn) {
        this.sourceColumn = sourceColumn;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    public FunctionKind getDefaultAggregation() {
        return defaultAggregation;
    }

    public void setDefaultAggregation(FunctionKind defaultAggregation) {
        this.defaultAggregation = defaultAggregation;
    }

    public DataColumnType getDataType() {
        return dataType;
    }

    public void setDataType(DataColumnType dataType) {
        this.dataType = dataType;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
