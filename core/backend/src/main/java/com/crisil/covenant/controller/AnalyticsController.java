package com.crisil.covenant.controller;

import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.dto.CpCard;
import com.crisil.covenant.dto.PortfolioAnalytics;
import com.crisil.covenant.dto.ResultTrendPoint;
import com.crisil.covenant.service.AnalyticsService;
import com.crisil.covenant.service.CpDirectoryService;
import com.crisil.covenant.service.TrendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FR-25: portfolio analytics for the caller's CPs (trend per covenant is the assessment history). */
@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics", description = "Portfolio risk by segment and sector")
public class AnalyticsController {

    private final AnalyticsService service;
    private final TrendService trendService;
    private final CpDirectoryService directory;

    public AnalyticsController(AnalyticsService service, TrendService trendService, CpDirectoryService directory) {
        this.service = service;
        this.trendService = trendService;
        this.directory = directory;
    }

    @GetMapping("/cps")
    @Operation(summary = "The caller's CPs with borrower name, segment, sector, RM and covenant counts")
    public List<CpCard> cps() {
        return directory.cps(CurrentUserContext.currentUser().orElse(null));
    }

    @GetMapping("/trend")
    @Operation(summary = "C9: check results per month (compliant, breached, error) for the caller's CPs")
    public List<ResultTrendPoint> trend(
            @Parameter(description = "How many months back, 1-36") @RequestParam(defaultValue = "12") int months) {
        return trendService.monthly(CurrentUserContext.currentUser().orElse(null), Math.max(1, Math.min(36, months)));
    }

    @GetMapping("/portfolio")
    @Operation(summary = "FR-25: breach and error counts by business segment and sector")
    public PortfolioAnalytics portfolio() {
        return service.portfolio(CurrentUserContext.currentUser().orElse(null));
    }
}
