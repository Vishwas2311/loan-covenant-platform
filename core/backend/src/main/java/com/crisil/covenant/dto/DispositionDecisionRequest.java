package com.crisil.covenant.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * FR-12: an approver's decision on a REQUESTED Waiver/Extension. Coverage-excluded ({@code *Request}).
 */
public record DispositionDecisionRequest(@NotNull Decision decision, @Size(max = 1000) String note) {

    public enum Decision { APPROVE, REJECT }
}
