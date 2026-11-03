package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crisil.covenant.domain.CovenantOnCp;
import com.crisil.covenant.dto.CovenantResponse;
import com.crisil.covenant.dto.PeriodComparison;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.repository.AssessmentRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ComparisonServiceTest {

    @Mock private MonitoringService monitoringService;
    @Mock private AssessmentRepository assessmentRepo;
    @Mock private CovenantMasterService masterService;

    private ComparisonService service() {
        return new ComparisonService(monitoringService, assessmentRepo, masterService);
    }

    private static CovenantOnCp covenant(String id, String name) {
        return new CovenantOnCp(id, "m", name, CovenantCategory.FINANCIAL, null, LocalDate.now(), null, null, null,
                AssessmentResult.COMPLIANT, Instant.now(), null, null, null, 0L, "DSCR >= 1", null, null, null);
    }

    private static Assessment assessment(String id, String acId, String executed, String scheduled,
                                         AssessmentResult result, String value) {
        AppliedCovenant ac = new AppliedCovenant();
        ReflectionTestUtils.setField(ac, "id", acId);
        Assessment a = new Assessment();
        ReflectionTestUtils.setField(a, "id", id);
        a.setAppliedCovenant(ac);
        if (executed != null) {
            a.setExecutedAt(Instant.parse(executed));
        }
        if (scheduled != null) {
            a.setScheduledAt(Instant.parse(scheduled));
        }
        a.setResult(result);
        a.setComputedValue(value);
        return a;
    }

    @Test
    void lastPeriodsNewestFirstWithGaps() {
        when(monitoringService.covenantsForCp("CP-1")).thenReturn(List.of(covenant("ac1", "DSCR"),
                covenant("ac2", "ICR")));
        when(assessmentRepo.findByAppliedCovenantCpIdOrderByScheduledAtDesc("CP-1")).thenReturn(List.of(
                assessment("a3", "ac1", "2026-09-30T10:00:00Z", null, AssessmentResult.BREACHED, "1.1"),
                assessment("a2", "ac1", null, "2026-06-30T02:00:00Z", AssessmentResult.COMPLIANT, "1.4"),
                assessment("a2b", "ac1", "2026-06-30T01:00:00Z", null, AssessmentResult.ERROR, null),
                assessment("a1", "ac2", "2026-03-31T10:00:00Z", null, AssessmentResult.COMPLIANT, "3"),
                assessment("a0", "ac2", null, null, AssessmentResult.COMPLIANT, "3")));

        PeriodComparison out = service().compare("CP-1", 2);

        assertThat(out.periods()).containsExactly(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 6, 30));
        assertThat(out.rows()).hasSize(2);
        assertThat(out.rows().get(0).cells().get(0).result()).isEqualTo(AssessmentResult.BREACHED);
        assertThat(out.rows().get(0).cells().get(1).assessmentId()).isEqualTo("a2");
        assertThat(out.rows().get(1).cells()).containsOnlyNulls();
        assertThat(service().compare("CP-1", 0).periods()).hasSize(1);
        assertThat(service().compare("CP-1", 99).periods()).hasSize(3);
    }

    @Test
    void trendFollowsTheRuleDirection() {
        PeriodComparison.Cell c11 = new PeriodComparison.Cell(AssessmentResult.COMPLIANT, "1.1", "a");
        PeriodComparison.Cell c15 = new PeriodComparison.Cell(AssessmentResult.COMPLIANT, "1.5", "b");
        PeriodComparison.Cell c151 = new PeriodComparison.Cell(AssessmentResult.COMPLIANT, "1.51", "c");
        PeriodComparison.Cell text = new PeriodComparison.Cell(AssessmentResult.COMPLIANT, "n/a", "d");
        PeriodComparison.Cell none = new PeriodComparison.Cell(AssessmentResult.ERROR, null, "e");
        assertThat(ComparisonService.trend("DSCR >= 1", java.util.Arrays.asList(c15, null, c11)))
                .isEqualTo(PeriodComparison.Trend.IMPROVING);
        assertThat(ComparisonService.trend("DSCR >= 1", List.of(c11, c15))).isEqualTo(PeriodComparison.Trend.WORSENING);
        assertThat(ComparisonService.trend("LEV <= 3", List.of(c11, c15))).isEqualTo(PeriodComparison.Trend.IMPROVING);
        assertThat(ComparisonService.trend("LEV <= 3", List.of(c15, c11))).isEqualTo(PeriodComparison.Trend.WORSENING);
        assertThat(ComparisonService.trend(null, List.of(c151, c15))).isEqualTo(PeriodComparison.Trend.STABLE);
        assertThat(ComparisonService.trend("A > 1 AND B < 2", List.of(c15, c11)))
                .isEqualTo(PeriodComparison.Trend.IMPROVING);
        assertThat(ComparisonService.trend("X", List.of(c15, text, none))).isNull();
    }

    @Test
    void excelExports() {
        when(monitoringService.covenantsForCp("CP-1")).thenReturn(List.of(covenant("ac1", "DSCR")));
        when(assessmentRepo.findByAppliedCovenantCpIdOrderByScheduledAtDesc("CP-1")).thenReturn(List.of(
                assessment("a1", "ac1", "2026-09-30T10:00:00Z", null, AssessmentResult.BREACHED, "1.1"),
                assessment("a0", "ac1", "2026-06-30T10:00:00Z", null, AssessmentResult.ERROR, null)));
        assertThat(service().compareXlsx("CP-1", 4)).isNotEmpty();

        CovenantResponse c = mock(CovenantResponse.class);
        when(c.name()).thenReturn("Minimum DSCR");
        when(c.active()).thenReturn(true);
        CovenantResponse d = mock(CovenantResponse.class);
        when(d.name()).thenReturn("Old");
        when(d.conditionExpression()).thenReturn("X > 1");
        when(d.description()).thenReturn("d");
        when(masterService.search(any(), any(), any(), any(), eq(PageRequest.of(0, 200))))
                .thenReturn(new PageImpl<>(List.of(c), PageRequest.of(0, 1), 2));
        when(masterService.search(any(), any(), any(), any(), eq(PageRequest.of(1, 200))))
                .thenReturn(new PageImpl<>(List.of(d), PageRequest.of(1, 1), 2));
        assertThat(service().libraryXlsx()).isNotEmpty();
    }
}
