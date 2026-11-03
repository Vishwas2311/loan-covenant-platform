package com.crisil.covenant.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** FR-23: record (or correct) one FX rate. Currencies are ISO 4217 upper-case codes. Coverage-excluded. */
public record FxRateRequest(
        @NotNull @Pattern(regexp = "[A-Z]{3}") String fromCurrency,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String toCurrency,
        @NotNull LocalDate rateDate,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal rate,
        @Size(max = 64) String source) {
}
