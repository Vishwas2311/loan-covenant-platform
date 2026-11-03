package com.crisil.covenant.controller;

import com.crisil.covenant.security.CpAccessGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.MlCovenantContext;
import com.crisil.covenant.dto.MlRecommendationRequest;
import com.crisil.covenant.dto.MlThresholdRecommendation;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.crisil.covenant.service.MlThresholdService;

@ExtendWith(MockitoExtension.class)
class MlThresholdControllerTest {

    @Mock private MlThresholdService service;
    @Mock private CpAccessGuard guard;
    @InjectMocks private MlThresholdController controller;

    @Test
    void recommendDelegatesToServiceAndReturnsRecommendations() {
        List<MlCovenantContext> items =
                List.of(new MlCovenantContext("1", "DSCR", "DSCR > 1.5", null));
        MlRecommendationRequest request = new MlRecommendationRequest(items);
        MlThresholdRecommendation rec =
                new MlThresholdRecommendation("1", "DSCR > 1.6", new BigDecimal("0.90"), "tighter");
        when(service.recommend(eq("CP-1"), eq(items))).thenReturn(List.of(rec));

        List<MlThresholdRecommendation> out = controller.recommend("CP-1", request);

        assertThat(out).containsExactly(rec);
        verify(service).recommend("CP-1", items);
    }
}
