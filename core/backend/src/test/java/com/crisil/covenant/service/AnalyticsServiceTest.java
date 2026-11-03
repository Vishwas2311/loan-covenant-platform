package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.crisil.covenant.domain.CpMonitoringSummary;
import com.crisil.covenant.dto.PortfolioAnalytics;
import com.crisil.covenant.entity.CreditProposalCacheEntry;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock private MonitoringService monitoringService;
    @Mock private CreditProposalCacheRepository cacheRepo;

    private static CreditProposalCacheEntry entry(String cp, String segment, String sector) throws Exception {
        var ctor = CreditProposalCacheEntry.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        CreditProposalCacheEntry e = ctor.newInstance();
        ReflectionTestUtils.setField(e, "cpId", cp);
        ReflectionTestUtils.setField(e, "businessSegment", segment);
        ReflectionTestUtils.setField(e, "sector", sector);
        return e;
    }

    @Test
    void groupsBySegmentAndSectorWorstFirst() throws Exception {
        when(monitoringService.summary("rm")).thenReturn(List.of(
                new CpMonitoringSummary("CP-1", 4, 2, 2, 0, 0, 1),
                new CpMonitoringSummary("CP-2", 3, 3, 0, 0, 0, 0),
                new CpMonitoringSummary("CP-3", 2, 0, 0, 2, 0, 0)));
        when(cacheRepo.findAllById(any())).thenReturn(List.of(
                entry("CP-1", "CORPORATE", "STEEL"), entry("CP-2", "CORPORATE", " ")));

        PortfolioAnalytics out = new AnalyticsService(monitoringService, cacheRepo).portfolio("rm");

        assertThat(out.total().cps()).isEqualTo(3);
        assertThat(out.total().applied()).isEqualTo(9);
        assertThat(out.total().breachRate()).isEqualTo(0.2857);
        assertThat(out.bySegment()).extracting(PortfolioAnalytics.Group::key).containsExactly("CORPORATE", "UNKNOWN");
        assertThat(out.bySegment().get(0).breachRate()).isEqualTo(0.2857);
        assertThat(out.bySegment().get(1).breachRate()).isZero();
        assertThat(out.bySector()).extracting(PortfolioAnalytics.Group::key).containsExactly("STEEL", "UNKNOWN");
        assertThat(out.bySector().get(0).dispositioned()).isEqualTo(1);
    }

    @Test
    void emptyPortfolio() {
        when(monitoringService.summary(null)).thenReturn(List.of());
        PortfolioAnalytics out = new AnalyticsService(monitoringService, cacheRepo).portfolio(null);
        assertThat(out.total().cps()).isZero();
        assertThat(out.bySegment()).isEmpty();
    }
}
