package com.crisil.covenant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class EligibilityTest {

    @Test
    void wildcardWhenNullEmptyOrContainsAll() {
        assertThat(Eligibility.isWildcard(null)).isTrue();
        assertThat(Eligibility.isWildcard(List.of())).isTrue();
        assertThat(Eligibility.isWildcard(List.of("ALL"))).isTrue();
        assertThat(Eligibility.isWildcard(List.of("all"))).isTrue();
        assertThat(Eligibility.isWildcard(List.of(" All "))).isTrue();
        assertThat(Eligibility.isWildcard(List.of("RETAIL", "ALL"))).isTrue();
        assertThat(Eligibility.isWildcard(List.of("RETAIL"))).isFalse();
    }

    @Test
    void normalizeCollapsesWildcardAndEmpty() {
        assertThat(Eligibility.normalize(null)).containsExactly("ALL");
        assertThat(Eligibility.normalize(List.of())).containsExactly("ALL");
        assertThat(Eligibility.normalize(List.of("ALL", "RETAIL"))).containsExactly("ALL");
        assertThat(Eligibility.normalize(Arrays.asList("  ", null))).containsExactly("ALL");
    }

    @Test
    void normalizeKeepsDistinctSpecificValuesInOrder() {
        assertThat(Eligibility.normalize(List.of("RETAIL", "SME", "RETAIL")))
                .containsExactly("RETAIL", "SME");
        assertThat(Eligibility.normalize(Arrays.asList("RETAIL", " SME ", null)))
                .containsExactly("RETAIL", "SME");
    }

    @Test
    void matchesValueHonoursWildcardAndMembership() {
        assertThat(Eligibility.matchesValue(List.of("ALL"), "IN")).isTrue();
        assertThat(Eligibility.matchesValue(List.of("IN", "AE"), "in")).isTrue();
        assertThat(Eligibility.matchesValue(List.of("IN", "AE"), "US")).isFalse();
        assertThat(Eligibility.matchesValue(List.of("IN"), null)).isFalse();
        assertThat(Eligibility.matchesValue(List.of("IN"), "  ")).isFalse();
    }

    @Test
    void intersectsHonoursWildcardAndOverlap() {
        assertThat(Eligibility.intersects(List.of("ALL"), List.of("X"))).isTrue();
        assertThat(Eligibility.intersects(List.of("TERM_LOAN", "GUARANTEE"),
                List.of("CASH_CREDIT", "guarantee"))).isTrue();
        assertThat(Eligibility.intersects(List.of("TERM_LOAN"), List.of("CASH_CREDIT"))).isFalse();
        assertThat(Eligibility.intersects(List.of("TERM_LOAN"), List.of())).isFalse();
        assertThat(Eligibility.intersects(List.of("TERM_LOAN"), null)).isFalse();
    }
}
