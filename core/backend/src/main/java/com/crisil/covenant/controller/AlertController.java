package com.crisil.covenant.controller;

import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.dto.AlertView;
import com.crisil.covenant.service.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/alerts")
@Tag(name = "Alerts", description = "What needs the signed-in user's attention")
public class AlertController {

    private final AlertService service;

    public AlertController(AlertService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Alerts for the signed-in user across the CPs they look after",
            description = "Waiver/Extension requests to decide, undecided breaches, overdue checks, "
                    + "person-checked covenants to record and covenants close to their limit.")
    public List<AlertView> alerts() {
        return service.alertsFor(CurrentUserContext.currentUser().orElse(null));
    }
}
