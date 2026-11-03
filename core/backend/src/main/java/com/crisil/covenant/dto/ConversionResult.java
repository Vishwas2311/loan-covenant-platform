package com.crisil.covenant.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FR-23: an amount converted for display. {@code rateDate} is the date of the rate used (null when the
 * currencies are the same); {@code inverse} is true when the rate on file was in the opposite direction.
 */
public record ConversionResult(BigDecimal amount, String fromCurrency, BigDecimal converted, String toCurrency,
                               BigDecimal rate, LocalDate rateDate, boolean inverse) {
}
