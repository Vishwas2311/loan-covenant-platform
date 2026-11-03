package com.crisil.covenant.dto;

import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.enums.TableEntityKeyLevel;
import com.crisil.covenant.enums.TableLayout;
import java.time.Instant;

public record DataSourceTableResponse(
        String id,
        String dataSourceId,
        String name,
        String schemaName,
        String tableName,
        String entityKeyColumn,
        String asOfColumn,
        TableLayout layout,
        String codeColumn,
        String valueColumn,
        TableEntityKeyLevel entityKeyLevel,
        boolean active,
        Instant createdDate,
        String createdBy,
        Instant modifiedDate,
        String modifiedBy,
        String version) {

    public static DataSourceTableResponse from(DataSourceTable t) {
        return new DataSourceTableResponse(
                t.getId(),
                t.getDataSource() == null ? null : t.getDataSource().getId(),
                t.getName(),
                t.getSchemaName(),
                t.getTableName(),
                t.getEntityKeyColumn(),
                t.getAsOfColumn(),
                t.getLayout(),
                t.getCodeColumn(),
                t.getValueColumn(),
                t.getEntityKeyLevel(),
                t.isActive(),
                t.getCreatedDate(),
                t.getCreatedBy(),
                t.getModifiedDate(),
                t.getModifiedBy(),
                t.getVersion());
    }
}
