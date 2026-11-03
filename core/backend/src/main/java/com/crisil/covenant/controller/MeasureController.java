package com.crisil.covenant.controller;

import com.crisil.covenant.dto.CreateMeasureRequest;
import com.crisil.covenant.dto.MeasureResponse;
import com.crisil.covenant.dto.UpdateMeasureRequest;
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
import com.crisil.covenant.entity.Measure;
import com.crisil.covenant.service.MeasureService;

@RestController
@RequestMapping("/api/v1/measures")
@Tag(name = "Measures", description = "Reusable measures (DSCR, PAT, EBITDA …) referenced in formulas")
public class MeasureController {

    private final MeasureService service;

    public MeasureController(MeasureService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create a measure",
            description = "Registers a new measure that formulas can reference by its unique code. "
                    + "Returns 201 with the created measure and a Location header pointing at it.")
    @ApiResponse(responseCode = "201", description = "Measure created",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = MeasureResponse.class)))
    public ResponseEntity<MeasureResponse> create(@Valid @RequestBody CreateMeasureRequest request) {
        MeasureResponse created = service.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/measures/" + created.id()))
                .body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a measure",
            description = "Updates the mutable attributes (name, unit, default aggregation …) of an "
                    + "existing measure.")
    public MeasureResponse update(@Parameter(description = "Measure id") @PathVariable String id,
                                  @Valid @RequestBody UpdateMeasureRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Deactivate a measure (soft delete)",
            description = "Marks the measure inactive so it can no longer be picked in new formulas; "
                    + "existing references are preserved.")
    public MeasureResponse deactivate(@Parameter(description = "Measure id") @PathVariable String id) {
        return service.setActive(id, false);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a measure by id")
    public MeasureResponse get(@Parameter(description = "Measure id") @PathVariable String id) {
        return service.get(id);
    }

    @GetMapping
    @Operation(summary = "List measures",
            description = "Returns all measures, or only the active ones when activeOnly=true.")
    public List<MeasureResponse> list(
            @Parameter(description = "When true, return only active measures")
            @RequestParam(required = false) Boolean activeOnly) {
        return service.list(activeOnly);
    }
}
