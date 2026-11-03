package com.crisil.covenant.controller;

import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.entity.ExpressionChangeLog;
import com.crisil.covenant.dto.CovenantResponse;
import com.crisil.covenant.dto.CovenantUsageResponse;
import com.crisil.covenant.dto.CreateCovenantRequest;
import com.crisil.covenant.dto.UpdateCovenantRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
import com.crisil.covenant.service.CovenantMasterService;

@RestController
@RequestMapping("/api/v1/covenants")
@Tag(name = "Covenant Master", description = "Library of covenant rule templates")
public class CovenantMasterController {

    private final CovenantMasterService service;

    public CovenantMasterController(CovenantMasterService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create a covenant in the master library",
            description = "Creates a reusable covenant rule template (condition expression, category "
                    + "and eligibility). Returns 201 with a Location header pointing at the new row.")
    @ApiResponse(responseCode = "201", description = "Covenant created",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = CovenantResponse.class)))
    public ResponseEntity<CovenantResponse> create(@Valid @RequestBody CreateCovenantRequest request) {
        CovenantResponse created = service.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/covenants/" + created.id()))
                .body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing covenant",
            description = "Updates a covenant template; changes to the condition expression are "
                    + "recorded in the expression change log.")
    public CovenantResponse update(@Parameter(description = "Covenant id") @PathVariable String id,
                                   @Valid @RequestBody UpdateCovenantRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Deactivate a covenant (soft delete)",
            description = "Marks the covenant inactive so it is no longer recommended or applied; "
                    + "existing applications on CPs are preserved.")
    public CovenantResponse deactivate(@Parameter(description = "Covenant id") @PathVariable String id) {
        return service.setActive(id, false);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a covenant by id")
    public CovenantResponse get(@Parameter(description = "Covenant id") @PathVariable String id) {
        return service.get(id);
    }

    @GetMapping
    @Operation(summary = "Search and filter covenants (paginated)",
            description = "Full-text search over covenant name/description with optional category, "
                    + "business-segment and active filters. Returns a Spring Data page.")
    public Page<CovenantResponse> search(
            @Parameter(description = "Free-text match on name/description")
            @RequestParam(required = false) String search,
            @Parameter(description = "Filter by covenant category (FINANCIAL / NON_FINANCIAL)")
            @RequestParam(required = false) CovenantCategory category,
            @Parameter(description = "Filter by an applicable business segment")
            @RequestParam(required = false) String businessSegment,
            @Parameter(description = "Filter by active flag; omit for all")
            @RequestParam(required = false) Boolean active,
            @PageableDefault(size = 25) Pageable pageable) {
        return service.search(search, category, businessSegment, active, pageable);
    }

    @GetMapping("/{id}/audit")
    @Operation(summary = "Read the expression change log for a covenant",
            description = "Returns the append-only history of condition-expression edits for the covenant.")
    public List<ExpressionChangeLog> audit(@Parameter(description = "Covenant id") @PathVariable String id) {
        return service.auditTrail(id);
    }

    @GetMapping("/{id}/usage")
    @Operation(summary = "How many CPs currently use this covenant (active + total)",
            description = "Reports how many Credit Proposals reference the covenant, split into active "
                    + "and total counts — used to gate deactivation.")
    public CovenantUsageResponse usage(@Parameter(description = "Covenant id") @PathVariable String id) {
        return service.usage(id);
    }
}
