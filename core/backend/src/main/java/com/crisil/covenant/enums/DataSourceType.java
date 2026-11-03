package com.crisil.covenant.enums;
import com.crisil.covenant.entity.DataSource;

/**
 * The kind of backing store a {@link DataSource} reads CP-keyed values from.
 */
public enum DataSourceType {
    /** A CP_ID-keyed REST endpoint (the existing {@code /api/data/values} contract). */
    REST,
    /** An MSSQL table with a CP_ID column, queried directly over JDBC. */
    MSSQL
}
