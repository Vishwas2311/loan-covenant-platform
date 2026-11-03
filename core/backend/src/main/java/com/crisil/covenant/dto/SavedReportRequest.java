package com.crisil.covenant.dto;

import com.crisil.covenant.entity.SavedReport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** C11: create or change a saved report. {@code emailTo} (comma-separated) is needed for a schedule. */
public record SavedReportRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull @Valid ReportDefinition definition,
        @NotNull SavedReport.Schedule schedule,
        @Size(max = 500) String emailTo) {
}
