package com.crisil.covenant.dto;

import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.formula.FunctionKind;
import com.crisil.covenant.entity.Measure;
import java.time.Instant;

public record MeasureResponse(
        String id,
        String code,
        String name,
        String description,
        String dataSourceTableId,
        String dataSourceId,
        String tableName,
        String sourceColumn,
        String sourceCode,
        FunctionKind defaultAggregation,
        DataColumnType dataType,
        String unit,
        boolean active,
        Instant createdDate,
        String createdBy,
        Instant modifiedDate,
        String modifiedBy,
        String version) {

    public static MeasureResponse from(Measure m) {
        var table = m.getDataSourceTable();
        return new MeasureResponse(
                m.getId(),
                m.getCode(),
                m.getName(),
                m.getDescription(),
                table == null ? null : table.getId(),
                table == null || table.getDataSource() == null ? null : table.getDataSource().getId(),
                table == null ? null : table.getTableName(),
                m.getSourceColumn(),
                m.getSourceCode(),
                m.getDefaultAggregation(),
                m.getDataType(),
                m.getUnit(),
                m.isActive(),
                m.getCreatedDate(),
                m.getCreatedBy(),
                m.getModifiedDate(),
                m.getModifiedBy(),
                m.getVersion());
    }
}
