package com.crisil.covenant.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** FR-22: require a certificate for {@code periodEnd}, due by {@code dueDate}. Coverage-excluded. */
public record CertificateRequirementRequest(
        @NotNull LocalDate periodEnd,
        @NotNull LocalDate dueDate,
        @Size(max = 1000) String note) {
}
