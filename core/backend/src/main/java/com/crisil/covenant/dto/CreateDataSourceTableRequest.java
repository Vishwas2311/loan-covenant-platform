package com.crisil.covenant.dto;

import com.crisil.covenant.enums.TableEntityKeyLevel;
import com.crisil.covenant.enums.TableLayout;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request to register a table under a data source. Identifiers must be safe SQL names. A null
 * {@code layout} defaults to {@code COLUMNAR}; {@code KEY_VALUE} tables must also supply
 * {@code codeColumn} + {@code valueColumn} (validated in the service). A null {@code entityKeyLevel}
 * defaults to {@code CP_ID} (the legacy grain); {@code OBLIGOR_ID} keys the table at obligor grain.
 */
public record CreateDataSourceTableRequest(
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
