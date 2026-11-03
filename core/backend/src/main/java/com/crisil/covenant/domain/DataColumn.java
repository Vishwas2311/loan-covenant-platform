package com.crisil.covenant.domain;
import com.crisil.covenant.entity.DataSource;
import com.crisil.covenant.enums.DataColumnType;

/**
 * A single selectable column exposed by a {@link DataSource}. The {@code key} is the
 * variable name referenced in covenant formulas (as {@code #key}).
 */
public record DataColumn(String key, String label, DataColumnType dataType, String description) {

    public static DataColumn of(String key, String label, DataColumnType dataType) {
        return new DataColumn(key, label, dataType, null);
    }
}
