package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.crisil.covenant.domain.CreditProposalDetails;
import com.crisil.covenant.dto.CpCycleView;
import com.crisil.covenant.entity.CreditProposalCacheEntry;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** US-MON-23: parent-link lineage walk — cache-first, live fallback, cycle/loop/depth guards. */
@ExtendWith(MockitoExtension.class)
class CpLineageServiceTest {

    @Mock private CreditProposalCacheRepository cacheRepo;
    @Mock private CreditProposalClient creditProposalClient;

    private CpLineageService service() {
        return new CpLineageService(cacheRepo, creditProposalClient);
    }

    private static CreditProposalCacheEntry entry(String cpId, String parent) {
        CreditProposalCacheEntry e = new CreditProposalCacheEntry();
        e.setCpId(cpId);
        e.setParentCpId(parent);
        return e;
    }

    private static CreditProposalDetails detailsWithParent(String parent) {
        return new CreditProposalDetails(null, null, null, null, null, null, null, null, null, parent);
    }

    @Test
    void walksCacheChainRootFirstWithCycleNumbersAndCurrentFlag() {
        when(cacheRepo.findById("CP-1001")).thenReturn(Optional.of(entry("CP-1001", "CP-1002")));
        when(cacheRepo.findById("CP-1002")).thenReturn(Optional.of(entry("CP-1002", "CP-1003")));
        when(cacheRepo.findById("CP-1003")).thenReturn(Optional.of(entry("CP-1003", null)));
        when(creditProposalClient.lookup("CP-1003")).thenReturn(null); // root: no parent

        List<CpCycleView> cycles = service().lineageFor("CP-1001");

        assertThat(cycles).extracting(CpCycleView::cycleNumber).containsExactly(1, 2, 3);
        assertThat(cycles).extracting(CpCycleView::cpId).containsExactly("CP-1003", "CP-1002", "CP-1001");
        assertThat(cycles).extracting(CpCycleView::current).containsExactly(false, false, true);
    }

    @Test
    void fallsBackToLiveLookupOnCacheMiss() {
        when(cacheRepo.findById("CP-A")).thenReturn(Optional.empty());
        when(cacheRepo.findById("CP-B")).thenReturn(Optional.empty());
        when(creditProposalClient.lookup("CP-A")).thenReturn(detailsWithParent("CP-B"));
        when(creditProposalClient.lookup("CP-B")).thenReturn(detailsWithParent(null));

        List<CpCycleView> cycles = service().lineageFor("CP-A");

        assertThat(cycles).extracting(CpCycleView::cpId).containsExactly("CP-B", "CP-A");
        assertThat(cycles.get(1).current()).isTrue();
    }

    @Test
    void endsChainGracefullyWhenLiveLookupThrows() {
        when(cacheRepo.findById("CP-A")).thenReturn(Optional.empty());
        when(creditProposalClient.lookup("CP-A")).thenThrow(new RuntimeException("CP service down"));

        List<CpCycleView> cycles = service().lineageFor("CP-A");

        assertThat(cycles).extracting(CpCycleView::cpId).containsExactly("CP-A");
        assertThat(cycles.get(0).current()).isTrue();
    }

    @Test
    void breaksAccidentalParentCycleViaVisitedSet() {
        when(cacheRepo.findById("CP-A")).thenReturn(Optional.of(entry("CP-A", "CP-B")));
        when(cacheRepo.findById("CP-B")).thenReturn(Optional.of(entry("CP-B", "CP-A"))); // loop back

        List<CpCycleView> cycles = service().lineageFor("CP-A");

        // Each CP appears once; the loop is broken rather than followed forever.
        assertThat(cycles).extracting(CpCycleView::cpId).containsExactly("CP-B", "CP-A");
    }

    @Test
    void singleCpWithNoParentYieldsOneCurrentCycle() {
        when(cacheRepo.findById("CP-A")).thenReturn(Optional.empty());
        when(creditProposalClient.lookup("CP-A")).thenReturn(null);

        List<CpCycleView> cycles = service().lineageFor("CP-A");

        assertThat(cycles).singleElement().satisfies(c -> {
            assertThat(c.cycleNumber()).isEqualTo(1);
            assertThat(c.cpId()).isEqualTo("CP-A");
            assertThat(c.current()).isTrue();
        });
    }

    @Test
    void blankCpIdYieldsEmptyLineage() {
        assertThat(service().lineageFor("")).isEmpty();
    }

    @Test
    void pathologicalChainIsCappedAtMaxDepth() {
        // Ever-growing unique ids ⇒ visited never trips; only MAX_DEPTH stops the walk.
        when(cacheRepo.findById(anyString()))
                .thenAnswer(inv -> Optional.of(entry(inv.getArgument(0), inv.getArgument(0) + "x")));

        List<CpCycleView> cycles = service().lineageFor("CP");

        assertThat(cycles).hasSize(CpLineageService.MAX_DEPTH);
        assertThat(cycles.get(0).cycleNumber()).isEqualTo(1);
        assertThat(cycles.get(CpLineageService.MAX_DEPTH - 1).cpId()).isEqualTo("CP");
        assertThat(cycles.get(CpLineageService.MAX_DEPTH - 1).current()).isTrue();
    }
}
