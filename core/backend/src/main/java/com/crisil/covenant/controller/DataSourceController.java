package com.crisil.covenant.controller;

import com.crisil.covenant.dto.CreateDataSourceRequest;
import com.crisil.covenant.dto.DataSourceResponse;
import com.crisil.covenant.dto.TestConnectionResult;
import com.crisil.covenant.dto.UpdateDataSourceRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.crisil.covenant.domain.DataColumn;
import com.crisil.covenant.service.DataSourceService;

@RestController
@RequestMapping("/api/v1/data-sources")
@Tag(name = "Data Sources", description = "Reusable CP_ID-keyed data sources (MSSQL or REST)")
public class DataSourceController {

    private final DataSourceService service;

    public DataSourceController(DataSourceService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Register a data source",
            description = "Registers a reusable MSSQL or REST data source that measures read their "
                    + "time-series values from. Returns 201 with a Location header.")
    @ApiResponse(responseCode = "201", description = "Data source created",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = DataSourceResponse.class)))
    public ResponseEntity<DataSourceResponse> create(@Valid @RequestBody CreateDataSourceRequest request) {
        DataSourceResponse created = service.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/data-sources/" + created.id()))
                .body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a data source",
            description = "Updates the connection details and metadata of an existing data source.")
    public DataSourceResponse update(@Parameter(description = "Data source id") @PathVariable String id,
                                     @Valid @RequestBody UpdateDataSourceRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Deactivate a data source (soft delete)",
            description = "Marks the data source inactive; existing tables and references are kept.")
    public DataSourceResponse deactivate(@Parameter(description = "Data source id") @PathVariable String id) {
        return service.setActive(id, false);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a data source by id")
    public DataSourceResponse get(@Parameter(description = "Data source id") @PathVariable String id) {
        return service.get(id);
    }

    @GetMapping
    @Operation(summary = "List data sources",
            description = "Returns all data sources, or only the active ones when activeOnly=true.")
    public List<DataSourceResponse> list(
            @Parameter(description = "When true, return only active data sources")
            @RequestParam(required = false) Boolean activeOnly) {
        return service.list(activeOnly);
    }

    @GetMapping("/{id}/columns")
    @Operation(summary = "Discover the selectable columns exposed by a data source",
            description = "Introspects the source (or its default table) and lists the columns that "
                    + "can be mapped to measures.")
    public List<DataColumn> columns(@Parameter(description = "Data source id") @PathVariable String id) {
        return service.columns(id);
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "Test connectivity and report the discovered column count",
            description = "Attempts a live connection to the source and returns whether it succeeded "
                    + "plus the number of columns discovered.")
    public TestConnectionResult testConnection(
            @Parameter(description = "Data source id") @PathVariable String id) {
        return service.testConnection(id);
    }
}
