package com.crisil.covenant.controller;

import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.dto.BreachReportRow;
import com.crisil.covenant.service.BreachReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FR-31/FR-32: escalation list and the open-breach management report. */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports", description = "Escalations and management reports")
public class ReportController {

    private final BreachReportService service;

    public ReportController(BreachReportService service) {
        this.service = service;
    }

    @GetMapping("/open-breaches")
    @Operation(summary = "FR-31: open breaches and errors, oldest and unhandled first, with escalation flag")
    public List<BreachReportRow> openBreaches(@RequestParam(defaultValue = "14") int escalationDays) {
        return service.openBreaches(CurrentUserContext.currentUser().orElse(null), Math.max(0, escalationDays));
    }

    @GetMapping("/open-breaches.pdf")
    @Operation(summary = "FR-32: the open-breach report as a PDF")
    public ResponseEntity<byte[]> openBreachesPdf(@RequestParam(defaultValue = "14") int escalationDays) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment().filename("open-breaches.pdf").build());
        return ResponseEntity.ok().headers(headers).body(service.openBreachesPdf(
                CurrentUserContext.currentUser().orElse(null), Math.max(0, escalationDays)));
    }

    @GetMapping("/open-breaches.xlsx")
    @Operation(summary = "FR-32: the open-breach report as an Excel file")
    public ResponseEntity<byte[]> openBreachesXlsx(@RequestParam(defaultValue = "14") int escalationDays) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(ExportController.XLSX);
        headers.setContentDisposition(ContentDisposition.attachment().filename("open-breaches.xlsx").build());
        return ResponseEntity.ok().headers(headers).body(service.openBreachesXlsx(
                CurrentUserContext.currentUser().orElse(null), Math.max(0, escalationDays)));
    }
}
