package com.crisil.covenant.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.enums.CovenantCategory;
import java.util.List;
import org.junit.jupiter.api.Test;

class CovenantDtoTest {

    @Test
    void createRequestExposesSafeListWhenNull() {
        CreateCovenantRequest r = new CreateCovenantRequest(
                "n", CovenantCategory.FINANCIAL, null, "DSCR > 1.2", null);
        assertThat(r.safeBusinessSegments()).isEmpty();
        assertThat(r.conditionExpression()).isEqualTo("DSCR > 1.2");
    }

    @Test
    void createRequestPreservesProvidedList() {
        CreateCovenantRequest r = new CreateCovenantRequest(
                "n", CovenantCategory.FINANCIAL, "d", "DSCR > 1.2", List.of("RETAIL"));
        assertThat(r.safeBusinessSegments()).containsExactly("RETAIL");
        assertThat(r.conditionExpression()).isEqualTo("DSCR > 1.2");
    }

    @Test
    void updateRequestSameSemanticsAsCreate() {
        UpdateCovenantRequest empty = new UpdateCovenantRequest(
                "n", CovenantCategory.NON_FINANCIAL, null, "auditor == 'PwC'", null);
        assertThat(empty.safeBusinessSegments()).isEmpty();

        UpdateCovenantRequest full = new UpdateCovenantRequest(
                "n", CovenantCategory.NON_FINANCIAL, "d", "auditor == 'PwC'",
                List.of("CORPORATE"));
        assertThat(full.safeBusinessSegments()).containsExactly("CORPORATE");
        assertThat(full.conditionExpression()).isEqualTo("auditor == 'PwC'");
    }
}
