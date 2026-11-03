package com.crisil.covenant.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A person tagged to a credit proposal (Relationship Manager / Wholesale Relationship Manager),
 * resolved from the CP source alongside the other CP details. Any field may be {@code null} when the
 * source omits it. Unknown JSON properties are ignored so the CP contract can evolve independently.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CpUser(String userId, String name, String email) {
}
