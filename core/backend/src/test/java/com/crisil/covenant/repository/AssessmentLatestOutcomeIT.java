package com.crisil.covenant.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.repository.AssessmentRepository.AssessmentOutcome;
import com.crisil.covenant.repository.AssessmentRepository.LatestOutcome;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The batched assessment queries against a real schema (H2 in MSSQL mode): the native ROW_NUMBER query
 * picks each covenant's newest assessment and maps its result to the enum, and the history query lists
 * outcomes newest first per covenant.
 */
@SpringBootTest
@ActiveProfiles("test")
class AssessmentLatestOutcomeIT {

    @Autowired private CovenantMasterRepository masterRepo;
    @Autowired private AppliedCovenantRepository appliedRepo;
    @Autowired private AssessmentRepository assessmentRepo;

    @Test
    void latestOutcomePerCovenantAndHistoryNewestFirst() {
        CovenantMaster master = new CovenantMaster();
        master.setName("Latest outcome IT DSCR");
        master.setCategory(CovenantCategory.FINANCIAL);
        master.setConditionExpression("DSCR > 1");
        master.setBusinessSegments(List.of());
        master.setActive(true);
        master = masterRepo.saveAndFlush(master);
        AppliedCovenant first = appliedRepo.saveAndFlush(applied("CP-LATEST-1", master));
        AppliedCovenant second = appliedRepo.saveAndFlush(applied("CP-LATEST-2", master));
        AppliedCovenant none = appliedRepo.saveAndFlush(applied("CP-LATEST-3", master));
        save(first, AssessmentResult.COMPLIANT, "2026-07-01T06:00:00Z");
        Assessment newest = save(first, AssessmentResult.BREACHED, "2026-10-01T06:00:00Z");
        save(first, AssessmentResult.ERROR, "2026-04-01T06:00:00Z");
        Assessment only = save(second, AssessmentResult.PENDING_REVIEW, "2026-09-01T06:00:00Z");

        Map<String, LatestOutcome> latest = assessmentRepo.findLatestOutcomes(
                List.of(first.getId(), second.getId(), none.getId())).stream()
                .collect(Collectors.toMap(LatestOutcome::getAppliedCovenantId, o -> o));

        assertThat(latest).containsOnlyKeys(first.getId(), second.getId());
        assertThat(latest.get(first.getId()).getId()).isEqualTo(newest.getId());
        assertThat(latest.get(first.getId()).getResult()).isEqualTo(AssessmentResult.BREACHED);
        assertThat(latest.get(second.getId()).getId()).isEqualTo(only.getId());

        List<AssessmentOutcome> history = assessmentRepo.findOutcomesByAppliedCovenantIdIn(List.of(first.getId()));
        assertThat(history).extracting(AssessmentOutcome::getResult)
                .containsExactly(AssessmentResult.BREACHED, AssessmentResult.COMPLIANT, AssessmentResult.ERROR);
    }

    private Assessment save(AppliedCovenant applied, AssessmentResult result, String scheduled) {
        Assessment a = new Assessment();
        a.setAppliedCovenant(applied);
        a.setScheduledAt(Instant.parse(scheduled));
        a.setResult(result);
        return assessmentRepo.saveAndFlush(a);
    }

    private static AppliedCovenant applied(String cpId, CovenantMaster master) {
        AppliedCovenant a = new AppliedCovenant();
        a.setCpId(cpId);
        a.setCovenantMaster(master);
        a.setFrequency(Frequency.DAILY);
        a.setCronExpression(Frequency.DAILY.defaultCron());
        a.setStartDate(LocalDate.now());
        a.setSnapshotJson("{}");
        return a;
    }
}
