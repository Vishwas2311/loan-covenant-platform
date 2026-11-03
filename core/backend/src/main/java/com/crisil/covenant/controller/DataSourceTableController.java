package com.crisil.covenant.controller;

import com.crisil.covenant.dto.CreateDataSourceTableRequest;
import com.crisil.covenant.dto.DataSourceTableResponse;
import com.crisil.covenant.dto.UpdateDataSourceTableRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.crisil.covenant.domain.DataColumn;
import com.crisil.covenant.service.DataSourceTableService;

@RestController
@RequestMapping("/api/v1/data-sources/{dataSourceId}/tables")
@Tag(name = "Data Source Tables", description = "Tables under a data source (each with its own key + as-of column)")
public class DataSourceTableController {

    private final DataSourceTableService service;

    public DataSourceTableController(DataSourceTableService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Register a table under a data source",
            description = "Adds a table (with its own key column and as-of/effective-date column) to "
                    + "the parent data source. Returns 201 with a Location header.")
    @ApiResponse(responseCode = "201", description = "Table created",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = DataSourceTableResponse.class)))
    public ResponseEntity<DataSourceTableResponse> create(
            @Parameter(description = "Parent data source id") @PathVariable String dataSourceId,
            @Valid @RequestBody CreateDataSourceTableRequest request) {
        DataSourceTableResponse created = service.create(dataSourceId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/data-sources/" + dataSourceId + "/tables/" + created.id()))
                .body(created);
    }

    @PutMapping("/{tableId}")
    @Operation(summary = "Update a table")
    public DataSourceTableResponse update(
            @Parameter(description = "Parent data source id") @PathVariable String dataSourceId,
            @Parameter(description = "Table id") @PathVariable String tableId,
            @Valid @RequestBody UpdateDataSourceTableRequest request) {
        return service.update(dataSourceId, tableId, request);
    }

    @DeleteMapping("/{tableId}")
    @Operation(summary = "Deactivate a table (soft delete)")
    public DataSourceTableResponse deactivate(
            @Parameter(description = "Parent data source id") @PathVariable String dataSourceId,
            @Parameter(description = "Table id") @PathVariable String tableId) {
        return service.setActive(dataSourceId, tableId, false);
    }

    @GetMapping("/{tableId}")
    @Operation(summary = "Get a table by id")
    public DataSourceTableResponse get(
            @Parameter(description = "Parent data source id") @PathVariable String dataSourceId,
            @Parameter(description = "Table id") @PathVariable String tableId) {
        return service.get(dataSourceId, tableId);
    }

    @GetMapping
    @Operation(summary = "List tables under a data source")
    public List<DataSourceTableResponse> list(
            @Parameter(description = "Parent data source id") @PathVariable String dataSourceId) {
        return service.list(dataSourceId);
    }

    @GetMapping("/{tableId}/columns")
    @Operation(summary = "Discover the columns of a table",
            description = "Introspects the table and lists its columns so they can be mapped to measures.")
    public List<DataColumn> columns(
            @Parameter(description = "Parent data source id") @PathVariable String dataSourceId,
            @Parameter(description = "Table id") @PathVariable String tableId) {
        return service.columns(dataSourceId, tableId);
    }

    @GetMapping("/{tableId}/code-values")
    @Operation(summary = "Discover the distinct measure codes in a KEY_VALUE table's code column",
            description = "For a KEY_VALUE-shaped table, returns the distinct measure codes present in "
                    + "its code column (used to map codes to measures).")
    public List<String> codeValues(
            @Parameter(description = "Parent data source id") @PathVariable String dataSourceId,
            @Parameter(description = "Table id") @PathVariable String tableId) {
        return service.codeValues(dataSourceId, tableId);
    }
}
