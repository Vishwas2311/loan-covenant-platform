package com.crisil.covenant.dto;

import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.enums.CovenantStatusSource;
import java.time.LocalDate;

/**
 * Request body for setting a covenant disposition (US-MON-16 PUT status). {@code status} must be
 * WAIVER or EXTENSION; {@code extensionDate} is mandatory for EXTENSION (AC9). {@code source}
 * defaults to USER when absent and is set to AI when applying an AI suggestion (US-MON-18).
 * Coverage-excluded ({@code *Request}).
 */
public record SetDispositionRequest(
        CovenantDispositionStatus status,
        LocalDate extensionDate,
        String note,
        CovenantStatusSource source) {
}
