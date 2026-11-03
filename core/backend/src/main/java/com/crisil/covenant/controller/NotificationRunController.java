package com.crisil.covenant.controller;

import com.crisil.covenant.config.AuditingConfig;
import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.dto.RunResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.crisil.covenant.service.BreachNotificationService;

/**
 * US-MON-14: the UI's manual trigger for the daily breach-assessment run. Mirrors how the Quartz
 * {@code DailyBreachRunJob} invokes the same {@link BreachNotificationService}, but attributes the run
 * to the acting user rather than {@code system}.
 */
@RestController
@RequestMapping("/api/v1/monitoring")
@Tag(name = "Monitoring", description = "Cross-CP monitoring dashboard")
public class NotificationRunController {

    private final BreachNotificationService service;

    public NotificationRunController(BreachNotificationService service) {
        this.service = service;
    }

    @PostMapping("/notification-runs")
    @Operation(summary = "US-MON-14: assess all covenants due on the given date (default today) and "
            + "email each RM (cc WRM) a per-CP monitoring summary, merged by (RM, WRM) pair",
            description = "Runs the daily breach assessment for the given date (today if omitted), then "
                    + "emails each RM (cc WRM) a per-CP summary. Returns counts of covenants assessed, "
                    + "CPs touched and notifications sent/failed. Attributed to the current user.")
    public RunResult run(
            @Parameter(description = "Assessment date (ISO); defaults to today when omitted")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        String user = CurrentUserContext.currentUser().orElse(AuditingConfig.SYSTEM_USER);
        return service.run(date, user);
    }
}
