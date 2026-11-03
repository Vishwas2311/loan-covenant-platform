package com.crisil.covenant.controller;

import com.crisil.covenant.dto.ops.HealthReport;
import com.crisil.covenant.service.OpsHealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Production-support health &amp; connectivity checks under the {@code X-Support-Token} gate. Probes
 * every registered data source (a REST catalog fetch or an MSSQL {@code INFORMATION_SCHEMA} read) so a
 * support engineer can confirm at a glance whether a covenant's data is failing because its source is
 * unreachable — versus reachable but empty. Read-only: writes and re-ingests nothing.
 */
@RestController
@RequestMapping("/api/v1/ops")
@Tag(name = "Ops Health",
        description = "Data-source health & connectivity checks (X-Support-Token gated)")
public class OpsHealthController {

    private final OpsHealthService service;

    public OpsHealthController(OpsHealthService service) {
        this.service = service;
    }

    @GetMapping("/health/data-sources")
    @Operation(summary = "Probe connectivity of every registered data source",
            description = "For each source runs the same lightweight probe as the admin test-connection "
                    + "(a REST catalog fetch or an MSSQL INFORMATION_SCHEMA read), reporting reachability, "
                    + "probe latency, and each source's registered table count, staged row count and "
                    + "last-ingest time. Writes nothing.")
    public HealthReport dataSourceHealth(
            @Parameter(description = "Probe only active sources (default true); false probes all")
            @RequestParam(name = "activeOnly", defaultValue = "true") boolean activeOnly) {
        return service.checkDataSources(activeOnly);
    }
}
