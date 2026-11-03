package com.crisil.covenant.formula.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request to extract the editable thresholds from a condition. */
public record ThresholdsRequest(@NotBlank @Size(max = 2000) String expression) {
}
