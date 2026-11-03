package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.crisil.covenant.entity.CreditProposalCacheEntry;
import com.crisil.covenant.entity.ObligorCpMap;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import com.crisil.covenant.repository.ObligorCpMapRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ObligorMappingService}: the (obligor -> many CPs) capture/resolve seam. The
 * mapping table is the source of truth; the obligor is denormalized onto the CP cache when a cache
 * row already exists. Recording is idempotent (upsert on cp_id) and never fabricates an obligor.
 */
@ExtendWith(MockitoExtension.class)
class ObligorMappingServiceTest {

    @Mock private ObligorCpMapRepository mappingRepo;
    @Mock private CreditProposalCacheRepository cacheRepo;
    @InjectMocks private ObligorMappingService service;

    @Test
    void recordMappingInsertsNewRowWhenCpUnmapped() {
        when(mappingRepo.findByCpId("CP-1")).thenReturn(Optional.empty());
        when(cacheRepo.findById("CP-1")).thenReturn(Optional.empty());

        service.recordMapping("CP-1", "OBL-1");

        ArgumentCaptor<ObligorCpMap> captor = ArgumentCaptor.forClass(ObligorCpMap.class);
        verify(mappingRepo).save(captor.capture());
        assertThat(captor.getValue().getCpId()).isEqualTo("CP-1");
        assertThat(captor.getValue().getObligorId()).isEqualTo("OBL-1");
        verify(cacheRepo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recordMappingUpdatesExistingRowInPlace() {
        ObligorCpMap existing = new ObligorCpMap();
        existing.setCpId("CP-1");
        existing.setObligorId("OBL-OLD");
        when(mappingRepo.findByCpId("CP-1")).thenReturn(Optional.of(existing));
        when(cacheRepo.findById("CP-1")).thenReturn(Optional.empty());

        service.recordMapping("CP-1", "OBL-NEW");

        // Idempotent upsert: the same row is reused (no duplicate) and its obligor is corrected.
        verify(mappingRepo).save(existing);
        assertThat(existing.getObligorId()).isEqualTo("OBL-NEW");
    }

    @Test
    void recordMappingDenormalizesObligorOntoCacheWhenPresent() {
        when(mappingRepo.findByCpId("CP-1")).thenReturn(Optional.empty());
        CreditProposalCacheEntry entry = new CreditProposalCacheEntry();
        entry.setCpId("CP-1");
        when(cacheRepo.findById("CP-1")).thenReturn(Optional.of(entry));

        service.recordMapping("CP-1", "OBL-1");

        verify(cacheRepo).save(entry);
        assertThat(entry.getObligorId()).isEqualTo("OBL-1");
    }

    @Test
    void recordMappingIgnoresBlankIdsAndNeverFabricates() {
        service.recordMapping(null, "OBL-1");
        service.recordMapping("CP-1", null);
        service.recordMapping("   ", "OBL-1");
        service.recordMapping("CP-1", "   ");

        verifyNoInteractions(mappingRepo, cacheRepo);
    }

    @Test
    void obligorForReturnsMappedObligor() {
        ObligorCpMap mapping = new ObligorCpMap();
        mapping.setCpId("CP-1");
        mapping.setObligorId("OBL-1");
        when(mappingRepo.findByCpId("CP-1")).thenReturn(Optional.of(mapping));

        assertThat(service.obligorFor("CP-1")).contains("OBL-1");
    }

    @Test
    void obligorForEmptyWhenCpUnmapped() {
        when(mappingRepo.findByCpId("CP-9")).thenReturn(Optional.empty());

        assertThat(service.obligorFor("CP-9")).isEmpty();
    }

    @Test
    void obligorForBlankShortCircuitsWithoutQuery() {
        assertThat(service.obligorFor("   ")).isEmpty();
        verifyNoInteractions(mappingRepo);
    }
}
