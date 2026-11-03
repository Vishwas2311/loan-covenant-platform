package com.crisil.covenant.controller;

import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.dto.PortfolioAnalytics;
import com.crisil.covenant.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-25: portfolio analytics for the caller's CPs (trend per covenant is the assessment history). */
@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics", description = "Portfolio risk by segment and sector")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/portfolio")
    @Operation(summary = "FR-25: breach and error counts by business segment and sector")
    public PortfolioAnalytics portfolio() {
        return service.portfolio(CurrentUserContext.currentUser().orElse(null));
    }
}
