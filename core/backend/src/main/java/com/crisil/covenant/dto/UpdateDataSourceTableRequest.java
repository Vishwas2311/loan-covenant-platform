package com.crisil.covenant.dto;

import com.crisil.covenant.enums.TableEntityKeyLevel;
import com.crisil.covenant.enums.TableLayout;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateDataSourceTableRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 128) @Pattern(regexp = "|[A-Za-z_][A-Za-z0-9_]*",
                message = "must be a valid SQL identifier") String schemaName,
        @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*",
                message = "must be a valid SQL identifier") String tableName,
        @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*",
                message = "must be a valid SQL identifier") String entityKeyColumn,
        @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*",
                message = "must be a valid SQL identifier") String asOfColumn,
        TableLayout layout,
        @Size(max = 128) @Pattern(regexp = "|[A-Za-z_][A-Za-z0-9_]*",
                message = "must be a valid SQL identifier") String codeColumn,
        @Size(max = 128) @Pattern(regexp = "|[A-Za-z_][A-Za-z0-9_]*",
                message = "must be a valid SQL identifier") String valueColumn,
        TableEntityKeyLevel entityKeyLevel) {
}
