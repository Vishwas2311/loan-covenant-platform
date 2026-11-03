package com.crisil.covenant.dto.ops;

import java.util.List;

/**
 * The flagship "why isn't it working" trace for a whole CP: every covenant applied to the CP, each
 * broken down per formula term with a {@link com.crisil.covenant.enums.DiagnosticReason}. Produced
 * entirely read-only — it mirrors the assessment read path without writing or re-ingesting anything.
 * Coverage-excluded ({@code dto/**}).
 *
 * @param cpId       the Credit Proposal id being diagnosed
 * @param obligorId  the CP's obligor, if one is recorded (informational; obligor-keyed tables read at
 *                   this grain), else {@code null}
 * @param covenantCount how many covenants are applied to the CP
 * @param covenants  the per-covenant diagnostics, ordered as applied
 */
public record CpDiagnosticsResponse(
        String cpId,
        String obligorId,
        int covenantCount,
        List<CovenantDiagnostic> covenants) {
}
