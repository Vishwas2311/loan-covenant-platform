package com.crisil.covenant.controller;

import com.crisil.covenant.dto.DataEventRequest;
import com.crisil.covenant.dto.DataEventResponse;
import com.crisil.covenant.service.DataEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C5 data-events from the Document Processing Service (role SERVICE in JWT mode). */
@RestController
@RequestMapping("/api/v1/data-events")
@Tag(name = "Data events", description = "New data from the Document Processing Service")
public class DataEventController {

    private final DataEventService service;

    public DataEventController(DataEventService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "C5: new data available for an entity; re-assess affected covenants",
            description = "Idempotent on (dataSourceId, entityKey, changedAt). Records the source change and "
                    + "re-assesses ACTIVE AUTO covenants that use measures from the data source.")
    public ResponseEntity<DataEventResponse> receive(@Valid @RequestBody DataEventRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.handle(request));
    }
}
