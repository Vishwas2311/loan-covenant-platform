package com.crisil.covenant.controller;

import com.crisil.covenant.config.AuditingConfig;
import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.dto.ManualRunView;
import com.crisil.covenant.service.ManualRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-MON-14: the UI's manual trigger for the daily breach-assessment run, the same sweep the Quartz
 * {@code DailyBreachRunJob} runs, attributed to the acting user rather than {@code system}. The run goes
 * on in the background: the POST answers 202 with the run's id at once, and the GET reports it.
 */
@RestController
@RequestMapping("/api/v1/monitoring")
@Tag(name = "Monitoring", description = "Cross-CP monitoring dashboard")
public class NotificationRunController {

    private final ManualRunService runs;

    public NotificationRunController(ManualRunService runs) {
        this.runs = runs;
    }

    @PostMapping("/notification-runs")
    @Operation(summary = "US-MON-14: start the daily breach assessment and RM e-mails for a date (default today)",
            description = "Starts the run in the background and answers 202 with its id and status RUNNING. "
                    + "Poll GET /notification-runs/{runId} for the counts. If a manual run is already going, "
                    + "that run is returned instead of starting another.")
    public ResponseEntity<ManualRunView> run(
            @Parameter(description = "Assessment date (ISO); defaults to today when omitted")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        String user = CurrentUserContext.currentUser().orElse(AuditingConfig.SYSTEM_USER);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(runs.start(date, user));
    }

    @GetMapping("/notification-runs/{runId}")
    @Operation(summary = "A manual daily run: RUNNING, then DONE with its counts or FAILED with the reason")
    public ResponseEntity<ManualRunView> status(@PathVariable String runId) {
        return runs.find(runId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
