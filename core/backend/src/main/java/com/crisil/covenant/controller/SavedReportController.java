package com.crisil.covenant.controller;

import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.dto.ReportDefinition;
import com.crisil.covenant.dto.SavedReportRequest;
import com.crisil.covenant.dto.SavedReportView;
import com.crisil.covenant.entity.SavedReport;
import com.crisil.covenant.service.ScheduledReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Custom reports", description = "C11: build, save, download and schedule your own report")
public class SavedReportController {

    private final ScheduledReportService service;

    public SavedReportController(ScheduledReportService service) {
        this.service = service;
    }

    private static String user() {
        return CurrentUserContext.currentUser().orElse(null);
    }

    @PostMapping("/run")
    @Operation(summary = "Run a report definition over the caller's CPs and return its rows")
    public List<Map<String, String>> run(@Valid @RequestBody ReportDefinition definition) {
        return service.run(user(), definition);
    }

    @GetMapping("/saved")
    @Operation(summary = "The caller's saved reports")
    public List<SavedReportView> saved() {
        return service.list(user());
    }

    @PostMapping("/saved")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Save a report, optionally e-mailed daily, weekly or monthly")
    public SavedReportView create(@Valid @RequestBody SavedReportRequest request) {
        return service.create(user(), request);
    }

    @PutMapping("/saved/{id}")
    @Operation(summary = "Change one of the caller's saved reports")
    public SavedReportView update(@PathVariable String id, @Valid @RequestBody SavedReportRequest request) {
        return service.update(user(), id, request);
    }

    @DeleteMapping("/saved/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete one of the caller's saved reports")
    public void delete(@PathVariable String id) {
        service.delete(user(), id);
    }

    @GetMapping("/saved/{id}/export.xlsx")
    @Operation(summary = "A saved report as an Excel file, run now")
    public ResponseEntity<byte[]> xlsx(@PathVariable String id) {
        SavedReport r = service.owned(user(), id);
        ReportDefinition def = service.definition(r);
        byte[] body = service.xlsx(r.getName(), def.chosenColumns(), service.run(user(), def));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(ExportController.XLSX);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(r.getName().replaceAll("[^A-Za-z0-9 _-]", "_") + ".xlsx").build());
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @PostMapping("/scheduled-runs")
    @Operation(summary = "E-mail every scheduled report that is due today (also runs in the daily sweep)")
    public Map<String, Integer> scheduledRun() {
        return Map.of("sent", service.sendDue(user() == null ? "system" : user()));
    }
}
