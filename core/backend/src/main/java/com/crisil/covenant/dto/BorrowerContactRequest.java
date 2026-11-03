package com.crisil.covenant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** C2: set who at the borrower gets certificate reminders, and how early. */
public record BorrowerContactRequest(
        @NotBlank @Size(max = 200) String contactName,
        @NotBlank @Email @Size(max = 254) String email,
        boolean remindersOn,
        @Min(1) @Max(60) int daysBefore) {
}
