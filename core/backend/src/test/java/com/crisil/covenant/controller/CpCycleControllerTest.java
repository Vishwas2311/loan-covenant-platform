package com.crisil.covenant.controller;

import com.crisil.covenant.security.CpAccessGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.CpCycleView;
import com.crisil.covenant.dto.CycleReviewResponse;
import com.crisil.covenant.service.CpCycleService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** US-MON-23: the read-only cycle-history controller delegates each endpoint to its service. */
@ExtendWith(MockitoExtension.class)
class CpCycleControllerTest {

    @Mock private CpCycleService cycleService;
    @Mock private CpAccessGuard guard;
    @InjectMocks private CpCycleController controller;

    @Test
    void cyclesDelegates() {
        List<CpCycleView> cycles = List.of(
                new CpCycleView(1, "CP-1003", false),
                new CpCycleView(2, "CP-1001", true));
        when(cycleService.cyclesFor("CP-1001")).thenReturn(cycles);

        assertThat(controller.cycles("CP-1001")).isSameAs(cycles);
        verify(cycleService).cyclesFor("CP-1001");
    }

    @Test
    void cycleReviewDelegates() {
        CycleReviewResponse response =
                new CycleReviewResponse("CP-1002", 2, "Reliance Industries Ltd", List.of(), List.of());
        when(cycleService.cycleReview("CP-1001", "CP-1002")).thenReturn(response);

        assertThat(controller.cycleReview("CP-1001", "CP-1002")).isSameAs(response);
        verify(cycleService).cycleReview("CP-1001", "CP-1002");
    }
}
