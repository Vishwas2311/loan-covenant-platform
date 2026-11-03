package com.crisil.covenant.dto;

import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.formula.FunctionKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request to create a measure. {@code code} must be a legal formula identifier. The mapping to
 * physical storage depends on the backing table's layout: COLUMNAR measures supply
 * {@code sourceColumn}, KEY_VALUE measures supply {@code sourceCode} (validated in the service).
 */
public record CreateMeasureRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*",
                message = "must be a valid identifier (letters, digits, underscore; not starting with a digit)")
        String code,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description,
        @NotNull String dataSourceTableId,
        @Size(max = 128) String sourceColumn,
        @NotNull FunctionKind defaultAggregation,
        @NotNull DataColumnType dataType,
        @Size(max = 32) String unit,
        @Size(max = 128) String sourceCode) {
}
