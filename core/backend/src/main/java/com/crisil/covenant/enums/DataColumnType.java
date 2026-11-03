package com.crisil.covenant.enums;
import com.crisil.covenant.domain.DataColumn;

/**
 * The logical data type of a {@link DataColumn}, used to drive sample-value generation
 * during rule validation and to inform the authoring UI.
 */
public enum DataColumnType {
    NUMBER,
    STRING,
    DATE,
    BOOLEAN
}
