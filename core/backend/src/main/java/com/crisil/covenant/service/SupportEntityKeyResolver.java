package com.crisil.covenant.service;

import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.enums.TableEntityKeyLevel;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The single source of truth for "which entity key does this table's staging use for this CP", shared
 * by every ops surface (diagnostics, ingestion-state listing, remediation, cleanup) so they can never
 * diverge from what {@code MeasureAggregationResolver} / {@code AssessmentService} actually read:
 * <ul>
 *   <li>a CP-keyed table binds the CP id directly;</li>
 *   <li>an obligor-keyed table binds the CP's obligor (shared across the obligor's CPs), or resolves
 *       to {@link Optional#empty()} when the CP has no {@code cov_obligor_cp_map} row.</li>
 * </ul>
 */
@Component
public class SupportEntityKeyResolver {

    private final ObligorMappingService obligorMappingService;

    public SupportEntityKeyResolver(ObligorMappingService obligorMappingService) {
        this.obligorMappingService = obligorMappingService;
    }

    /**
     * The entity key {@code table}'s staging rows are stored under for {@code cpId}, or empty when the
     * table is obligor-keyed and the CP has no recorded obligor (the resolver would then report the
     * term {@code present=false}).
     */
    public Optional<String> entityKeyFor(DataSourceTable table, String cpId) {
        if (table.getEntityKeyLevel() == TableEntityKeyLevel.OBLIGOR_ID) {
            return obligorMappingService.obligorFor(cpId);
        }
        return Optional.ofNullable(cpId);
    }
}
