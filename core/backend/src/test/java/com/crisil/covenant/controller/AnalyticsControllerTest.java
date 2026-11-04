package com.crisil.covenant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.CpCard;
import com.crisil.covenant.dto.PortfolioAnalytics;
import com.crisil.covenant.service.AnalyticsService;
import com.crisil.covenant.service.CpDirectoryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnalyticsControllerTest {

    @Mock AnalyticsService service;
    @Mock CpDirectoryService directory;
    @InjectMocks AnalyticsController controller;

    @Test
    void delegates() {
        PortfolioAnalytics out = new PortfolioAnalytics(null, List.of(), List.of());
        when(service.portfolio(any())).thenReturn(out);
        assertThat(controller.portfolio()).isSameAs(out);
    }

    @Test
    void listsCps() {
        List<CpCard> out = List.of();
        when(directory.cps(any())).thenReturn(out);
        assertThat(controller.cps()).isSameAs(out);
    }
}
