package com.crisil.covenant.dto;

import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.formula.FunctionKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateMeasureRequest(
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
