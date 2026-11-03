package com.crisil.covenant.dto;

import java.util.List;

/**
 * FR-25: the caller's portfolio broken down by business segment and by sector. {@code breachRate} is
 * breached / assessed covenants (0 when nothing has been assessed).
 */
public record PortfolioAnalytics(Group total, List<Group> bySegment, List<Group> bySector) {

    public record Group(String key, long cps, long applied, long compliant, long breached, long error,
                        long dispositioned, double breachRate) {
    }
}
