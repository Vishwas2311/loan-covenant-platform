package com.crisil.covenant.dto;

import com.crisil.covenant.enums.TableEntityKeyLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;

/** C5: the Document Processing Service reports new data for an entity (contracts/core-additions.yaml). */
public record DataEventRequest(
        @NotBlank @Size(max = 36) String dataSourceId,
        @NotBlank @Size(max = 128) String entityKey,
        @NotNull TableEntityKeyLevel entityKeyLevel,
        @NotNull LocalDate asOfDate,
        @NotNull Instant changedAt,
        @Size(max = 64) String documentId) {
}
