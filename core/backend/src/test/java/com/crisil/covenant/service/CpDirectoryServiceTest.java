package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.crisil.covenant.domain.CpMonitoringSummary;
import com.crisil.covenant.domain.CreditProposalDetails;
import com.crisil.covenant.dto.CpCard;
import com.crisil.covenant.entity.CreditProposalCacheEntry;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CpDirectoryServiceTest {

    @Mock private CreditProposalCacheRepository cacheRepo;
    @Mock private MonitoringService monitoringService;
    @Mock private CreditProposalClient creditProposals;

    private static CreditProposalCacheEntry entry(String cp, String segment) throws Exception {
        var ctor = CreditProposalCacheEntry.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        CreditProposalCacheEntry e = ctor.newInstance();
        ReflectionTestUtils.setField(e, "cpId", cp);
        ReflectionTestUtils.setField(e, "businessSegment", segment);
        ReflectionTestUtils.setField(e, "sector", "STEEL");
        ReflectionTestUtils.setField(e, "rmUserId", "rm");
        return e;
    }

    private static CreditProposalDetails details(String cp, String name) {
        return new CreditProposalDetails(cp, name, null, null, null, null, null, null, null, null);
    }

    @Test
    void listsOwnedCpsWithNamesAndCountsSortedById() throws Exception {
        when(cacheRepo.findCpIdsOwnedBy("rm")).thenReturn(List.of("CP-2", "CP-1"));
        when(cacheRepo.findAllById(any())).thenReturn(List.of(entry("CP-2", "CORPORATE"), entry("CP-1", "SME")));
        when(monitoringService.summary("rm")).thenReturn(List.of(new CpMonitoringSummary("CP-1", 5, 3, 1, 1, 0, 1)));
        when(creditProposals.lookup("CP-1")).thenReturn(details("CP-1", "Tata Steel"));
        when(creditProposals.lookup("CP-2")).thenThrow(new IllegalStateException("down"));

        CpDirectoryService svc = new CpDirectoryService(cacheRepo, monitoringService, creditProposals);
        List<CpCard> out = svc.cps("rm");

        assertThat(out).extracting(CpCard::cpId).containsExactly("CP-1", "CP-2");
        assertThat(out.get(0)).isEqualTo(new CpCard("CP-1", "Tata Steel", "SME", "STEEL", "rm", 5, 3, 1, 1, 0, 1));
        assertThat(out.get(1).name()).isNull();
        assertThat(out.get(1).applied()).isZero();

        // The name found is kept; the failed one is asked again.
        svc.cps("rm");
        verify(creditProposals, times(1)).lookup("CP-1");
        verify(creditProposals, times(2)).lookup("CP-2");
    }

    @Test
    void unknownNameIsNotKept() throws Exception {
        when(cacheRepo.findCpIdsOwnedBy("rm")).thenReturn(List.of("CP-1"));
        when(cacheRepo.findAllById(any())).thenReturn(List.of(entry("CP-1", null)));
        when(monitoringService.summary("rm")).thenReturn(List.of());
        when(creditProposals.lookup("CP-1")).thenReturn(null).thenReturn(details("CP-1", null));

        CpDirectoryService svc = new CpDirectoryService(cacheRepo, monitoringService, creditProposals);
        assertThat(svc.cps("rm").get(0).name()).isNull();
        assertThat(svc.cps("rm").get(0).name()).isNull();
    }

    @Test
    void noUserOrNoCpsGivesEmptyList() {
        CpDirectoryService svc = new CpDirectoryService(cacheRepo, monitoringService, creditProposals);
        assertThat(svc.cps(null)).isEmpty();
        assertThat(svc.cps(" ")).isEmpty();
        when(cacheRepo.findCpIdsOwnedBy("x")).thenReturn(List.of());
        assertThat(svc.cps("x")).isEmpty();
        verifyNoInteractions(monitoringService, creditProposals);
    }
}
