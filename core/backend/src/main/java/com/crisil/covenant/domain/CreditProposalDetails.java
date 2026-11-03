package com.crisil.covenant.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditProposalDetails(
        String cpId,
        String name,
        String businessSegment,
        String sector,
        String approvalStatus,
        List<String> tags,
        CpUser rm,
        CpUser wrm,
        String obligorId,
        String parentCpId) {

    public List<String> safeTags() {
        return tags == null ? List.of() : tags;
    }
}
