package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PortfolioCacheTest {

    @Test
    void aWriteDropsOnlyTheEntriesThatIncludeItsCp() {
        AtomicInteger loads = new AtomicInteger();
        PortfolioCache<List<String>, Integer> cache = new PortfolioCache<>(Duration.ofMinutes(1), 10);
        List<String> mine = List.of("PC-A1", "PC-A2");
        List<String> other = List.of("PC-B1");
        cache.get(mine, loads::incrementAndGet);
        cache.get(other, loads::incrementAndGet);
        assertThat(cache.get(mine, loads::incrementAndGet)).isEqualTo(1);

        PortfolioCache.changed("PC-B1");
        assertThat(cache.get(mine, loads::incrementAndGet)).isEqualTo(1); // untouched
        assertThat(cache.get(other, loads::incrementAndGet)).isEqualTo(3); // reloaded

        PortfolioCache.changed(null); // CP unknown: everything goes
        assertThat(cache.get(mine, loads::incrementAndGet)).isEqualTo(4);
    }

    @Test
    void entriesExpire() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        PortfolioCache<List<String>, Integer> cache = new PortfolioCache<>(Duration.ofMillis(1), 10);
        cache.get(List.of("PC-C1"), loads::incrementAndGet);
        Thread.sleep(5);
        assertThat(cache.get(List.of("PC-C1"), loads::incrementAndGet)).isEqualTo(2);
    }

    @Test
    void aValueReadInsideTheWritingTransactionIsDroppedWhenItEnds() {
        AtomicInteger loads = new AtomicInteger();
        PortfolioCache<List<String>, Integer> cache = new PortfolioCache<>(Duration.ofMinutes(1), 10);
        TransactionSynchronizationManager.initSynchronization();
        try {
            PortfolioCache.changed("PC-D1");
            cache.get(List.of("PC-D1"), loads::incrementAndGet);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(cache.get(List.of("PC-D1"), loads::incrementAndGet)).isEqualTo(2);
    }

    @Test
    void aFullCacheStartsAgain() {
        PortfolioCache<List<String>, Integer> cache = new PortfolioCache<>(Duration.ofMinutes(1), 2);
        cache.get(List.of("PC-E1"), () -> 1);
        cache.get(List.of("PC-E2"), () -> 2);
        cache.get(List.of("PC-E3"), () -> 3);
        assertThat(cache.get(List.of("PC-E1"), () -> 9)).isEqualTo(9);
    }

    @Test
    void theCpOfEachWrittenEntityIsKnown() {
        com.crisil.covenant.entity.CovenantAttachment evidence = new com.crisil.covenant.entity.CovenantAttachment(
                "PC-F1", "ac", "f", "n", "u", java.time.Instant.EPOCH);
        assertThat(CpChangeListener.cpOf(evidence)).isEqualTo("PC-F1");
        assertThat(CpChangeListener.cpOf("something else")).isNull();
    }
}
