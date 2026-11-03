package com.crisil.covenant.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.enums.Frequency;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import com.crisil.covenant.domain.MonitoringTiming;
import com.crisil.covenant.enums.TimingStatus;

class MonitoringTimingCalculatorTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final String MONTHLY = "0 0 2 1 * ?";   // 1st of month, 02:00
    private static final String DAILY = "0 0 2 * * ?";     // every day 02:00

    @Test
    void notStartedWhenStartDateIsFuture() {
        LocalDate today = LocalDate.of(2026, 6, 15);
        MonitoringTiming t = MonitoringTimingCalculator.compute(
                MONTHLY, LocalDate.of(2026, 8, 1), null, null, today, UTC);
        assertThat(t.status()).isEqualTo(TimingStatus.NOT_STARTED);
        assertThat(t.nextDueDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(t.overdueByDays()).isZero();
    }

    @Test
    void endedWhenEndDatePassed() {
        LocalDate today = LocalDate.of(2026, 6, 15);
        MonitoringTiming t = MonitoringTimingCalculator.compute(
                MONTHLY, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 1), null, today, UTC);
        assertThat(t.status()).isEqualTo(TimingStatus.ENDED);
        assertThat(t.nextDueDate()).isNull();
    }

    @Test
    void overdueWhenStartedAndUnassessedOccurrencePassed() {
        LocalDate today = LocalDate.of(2026, 6, 15);
        MonitoringTiming t = MonitoringTimingCalculator.compute(
                MONTHLY, LocalDate.of(2026, 1, 20), null, null, today, UTC);
        // First occurrence at/after 2026-01-20 is 2026-02-01, which is in the past with no assessment.
        assertThat(t.status()).isEqualTo(TimingStatus.OVERDUE);
        assertThat(t.nextDueDate()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(t.overdueByDays()).isEqualTo(134);
    }

    @Test
    void dueSoonWhenNextOccurrenceWithinSevenDays() {
        LocalDate today = LocalDate.of(2026, 6, 15);
        Instant lastAssessed = Instant.parse("2026-06-14T12:00:00Z");
        MonitoringTiming t = MonitoringTimingCalculator.compute(
                DAILY, LocalDate.of(2026, 6, 1), null, lastAssessed, today, UTC);
        // Next daily occurrence after the last assessment is 2026-06-15 (== today).
        assertThat(t.status()).isEqualTo(TimingStatus.DUE_SOON);
        assertThat(t.nextDueDate()).isEqualTo(today);
        assertThat(t.overdueByDays()).isZero();
    }

    @Test
    void upToDateWhenNextOccurrenceBeyondWindow() {
        LocalDate today = LocalDate.of(2026, 6, 15);
        Instant lastAssessed = Instant.parse("2026-06-02T00:00:00Z");
        MonitoringTiming t = MonitoringTimingCalculator.compute(
                MONTHLY, LocalDate.of(2026, 1, 1), null, lastAssessed, today, UTC);
        // Next monthly occurrence after 2026-06-02 is 2026-07-01 (> today + 7).
        assertThat(t.status()).isEqualTo(TimingStatus.UP_TO_DATE);
        assertThat(t.nextDueDate()).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    void upToDateWhenNextOccurrenceIsBeyondEndDate() {
        LocalDate today = LocalDate.of(2026, 6, 15);
        Instant lastAssessed = Instant.parse("2026-06-02T00:00:00Z");
        MonitoringTiming t = MonitoringTimingCalculator.compute(
                MONTHLY, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 20), lastAssessed, today, UTC);
        // Next occurrence (2026-07-01) is past the end date, so there is no upcoming due date.
        assertThat(t.status()).isEqualTo(TimingStatus.UP_TO_DATE);
        assertThat(t.nextDueDate()).isNull();
    }

    @Test
    void nullCronYieldsNoDueDate() {
        LocalDate today = LocalDate.of(2026, 6, 15);
        MonitoringTiming t = MonitoringTimingCalculator.compute(
                null, LocalDate.of(2026, 1, 1), null, null, today, UTC);
        assertThat(t.nextDueDate()).isNull();
        assertThat(t.status()).isEqualTo(TimingStatus.UP_TO_DATE);
    }

    @Test
    void invalidCronYieldsNoDueDate() {
        LocalDate today = LocalDate.of(2026, 6, 15);
        MonitoringTiming t = MonitoringTimingCalculator.compute(
                "not a cron", LocalDate.of(2026, 8, 1), null, null, today, UTC);
        assertThat(t.nextDueDate()).isNull();
        assertThat(t.status()).isEqualTo(TimingStatus.NOT_STARTED);
    }

    @Test
    void handlesNullStartDateNeverAssessed() {
        LocalDate today = LocalDate.of(2026, 6, 15);
        MonitoringTiming t = MonitoringTimingCalculator.compute(MONTHLY, null, null, null, today, UTC);
        assertThat(t.status()).isEqualTo(TimingStatus.NOT_STARTED);
        assertThat(t.nextDueDate()).isNotNull();
    }

    @Test
    void blankCronYieldsNoDueDate() {
        LocalDate today = LocalDate.of(2026, 6, 15);
        MonitoringTiming t = MonitoringTimingCalculator.compute(
                "   ", LocalDate.of(2026, 1, 1), null, null, today, UTC);
        assertThat(t.nextDueDate()).isNull();
        assertThat(t.status()).isEqualTo(TimingStatus.UP_TO_DATE);
    }

    @Test
    void cronWithNoFutureOccurrenceYieldsNoDueDate() {
        LocalDate today = LocalDate.of(2026, 6, 15);
        // 30 February never occurs, so there is no valid next fire time.
        MonitoringTiming t = MonitoringTimingCalculator.compute(
                "0 0 2 30 2 ?", LocalDate.of(2026, 1, 1), null, null, today, UTC);
        assertThat(t.nextDueDate()).isNull();
        assertThat(t.status()).isEqualTo(TimingStatus.UP_TO_DATE);
    }

    // --- US-MON-21: extension defers the next monitoring date ---------------------------------

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    @Test
    void withExtensionNullExtensionLeavesTimingUnchanged() {
        MonitoringTiming base = new MonitoringTiming(LocalDate.of(2026, 6, 5), TimingStatus.OVERDUE, 10);
        assertThat(MonitoringTimingCalculator.withExtension(base, null, TODAY, 7)).isSameAs(base);
    }

    @Test
    void withExtensionIgnoredWhenEnded() {
        MonitoringTiming base = new MonitoringTiming(null, TimingStatus.ENDED, 0);
        assertThat(MonitoringTimingCalculator.withExtension(base, LocalDate.of(2026, 7, 1), TODAY, 7))
                .isSameAs(base);
    }

    @Test
    void withExtensionIgnoredWhenNotStarted() {
        MonitoringTiming base = new MonitoringTiming(LocalDate.of(2026, 8, 1), TimingStatus.NOT_STARTED, 0);
        assertThat(MonitoringTimingCalculator.withExtension(base, LocalDate.of(2026, 7, 1), TODAY, 7))
                .isSameAs(base);
    }

    @Test
    void withExtensionDefersOverdueCovenantToGraceDate() {
        // Overdue (due date in the past); extension well into the future clears the overdue flag.
        MonitoringTiming base = new MonitoringTiming(LocalDate.of(2026, 6, 5), TimingStatus.OVERDUE, 10);
        MonitoringTiming t = MonitoringTimingCalculator.withExtension(
                base, LocalDate.of(2026, 7, 20), TODAY, 7);
        assertThat(t.status()).isEqualTo(TimingStatus.UP_TO_DATE);
        assertThat(t.nextDueDate()).isEqualTo(LocalDate.of(2026, 7, 20));
        assertThat(t.overdueByDays()).isZero();
    }

    @Test
    void withExtensionOverdueGraceWithinHorizonIsDueSoon() {
        MonitoringTiming base = new MonitoringTiming(LocalDate.of(2026, 6, 5), TimingStatus.OVERDUE, 10);
        MonitoringTiming t = MonitoringTimingCalculator.withExtension(
                base, LocalDate.of(2026, 6, 18), TODAY, 7); // within today+7
        assertThat(t.status()).isEqualTo(TimingStatus.DUE_SOON);
        assertThat(t.nextDueDate()).isEqualTo(LocalDate.of(2026, 6, 18));
    }

    @Test
    void withExtensionEarlierUpcomingFrequencyDateWins() {
        // Not overdue: a future frequency date earlier than the extension pre-empts the grace date.
        MonitoringTiming base = new MonitoringTiming(LocalDate.of(2026, 6, 30), TimingStatus.UP_TO_DATE, 0);
        MonitoringTiming t = MonitoringTimingCalculator.withExtension(
                base, LocalDate.of(2026, 7, 10), TODAY, 7);
        assertThat(t.nextDueDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(t.status()).isEqualTo(TimingStatus.UP_TO_DATE);
    }

    @Test
    void withExtensionUsesGraceWhenFrequencyIsLater() {
        MonitoringTiming base = new MonitoringTiming(LocalDate.of(2026, 7, 30), TimingStatus.UP_TO_DATE, 0);
        MonitoringTiming t = MonitoringTimingCalculator.withExtension(
                base, LocalDate.of(2026, 6, 20), TODAY, 7); // grace earlier than the next occurrence
        assertThat(t.nextDueDate()).isEqualTo(LocalDate.of(2026, 6, 20));
        assertThat(t.status()).isEqualTo(TimingStatus.DUE_SOON);
    }

    @Test
    void withExtensionUsesGraceWhenNoFrequencyDate() {
        MonitoringTiming base = new MonitoringTiming(null, TimingStatus.UP_TO_DATE, 0);
        MonitoringTiming t = MonitoringTimingCalculator.withExtension(
                base, LocalDate.of(2026, 7, 1), TODAY, 7);
        assertThat(t.nextDueDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(t.status()).isEqualTo(TimingStatus.UP_TO_DATE);
    }

    @Test
    void withExtensionRemainsOverdueWhenGraceDateInPastAndHorizonFallsBack() {
        // A grace date in the past keeps it overdue; dueSoonDays<=0 exercises the horizon fallback.
        MonitoringTiming base = new MonitoringTiming(LocalDate.of(2026, 6, 5), TimingStatus.OVERDUE, 10);
        MonitoringTiming t = MonitoringTimingCalculator.withExtension(
                base, LocalDate.of(2026, 6, 10), TODAY, 0);
        assertThat(t.status()).isEqualTo(TimingStatus.OVERDUE);
        assertThat(t.nextDueDate()).isEqualTo(LocalDate.of(2026, 6, 10));
        assertThat(t.overdueByDays()).isEqualTo(5);
    }

    @Test
    void effectiveCronPrefersStoredThenFrequencyThenNull() {
        assertThat(MonitoringTimingCalculator.effectiveCron(Frequency.DAILY, "0 0 5 * * ?"))
                .isEqualTo("0 0 5 * * ?");
        assertThat(MonitoringTimingCalculator.effectiveCron(Frequency.QUARTERLY, null))
                .isEqualTo(Frequency.QUARTERLY.defaultCron());
        assertThat(MonitoringTimingCalculator.effectiveCron(Frequency.QUARTERLY, "  "))
                .isEqualTo(Frequency.QUARTERLY.defaultCron());
        assertThat(MonitoringTimingCalculator.effectiveCron(Frequency.CUSTOM, null)).isNull();
    }
}
