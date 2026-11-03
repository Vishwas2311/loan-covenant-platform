package com.crisil.covenant.controller;

import com.crisil.covenant.security.CpAccessGuard;
import com.crisil.covenant.dto.MlRecommendationRequest;
import com.crisil.covenant.dto.MlThresholdRecommendation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.crisil.covenant.service.MlThresholdService;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "ML Thresholds", description = "ML-recommended covenant thresholds for a Credit Proposal")
public class MlThresholdController {

    private final CpAccessGuard guard;

    private final MlThresholdService service;

    public MlThresholdController(MlThresholdService service,
            CpAccessGuard guard) {
        this.guard = guard;
        this.service = service;
    }

    @PostMapping("/credit-proposals/{cpId}/ml-threshold-recommendations")
    @Operation(summary = "Ask the ML service to recommend thresholds for the in-scope covenants of a CP",
            description = "Sends the given covenants' contexts to the external ML service (behind a "
                    + "circuit breaker + retry + time limiter) and returns a recommended threshold "
                    + "expression, confidence and rationale per covenant.")
    public List<MlThresholdRecommendation> recommend(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId,
            @Valid @RequestBody MlRecommendationRequest request) {
        guard.requireCp(cpId);
        return service.recommend(cpId, request.items());
    }
}
