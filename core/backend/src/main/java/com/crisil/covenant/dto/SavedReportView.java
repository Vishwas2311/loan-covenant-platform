package com.crisil.covenant.dto;

import java.time.LocalDate;

/** C11: a saved report as the Reports page lists it. */
public record SavedReportView(String id, String name, ReportDefinition definition, String schedule, String emailTo,
                              LocalDate lastSentOn) {
}
