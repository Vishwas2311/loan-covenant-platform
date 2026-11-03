package com.crisil.covenant.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.service.CachedCovenantAttributesService;
import com.crisil.covenant.domain.CovenantAttributes;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.repository.CovenantMasterRepository;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import com.crisil.covenant.entity.CovenantRecommendation;
import com.crisil.covenant.enums.RecommendationStatus;
import com.crisil.covenant.service.RecommendationService;

@ExtendWith(MockitoExtension.class)
class RecommendationPersistenceTest {

    @Mock private CovenantMasterRepository masterRepo;
    @Mock private CovenantRecommendationRepository recommendationRepo;
    @Mock private CachedCovenantAttributesService attributesService;
    @InjectMocks private RecommendationService service;

    @Test
    void firstCallPersistsRowAsSuggested() {
        CovenantMaster cm = newCovenant("1", List.of("RETAIL"));
        when(attributesService.fetch("CP-1")).thenReturn(
                new CovenantAttributes("CP-1", "RETAIL", "S", Map.of()));
        when(masterRepo.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(cm));
        when(recommendationRepo.findByCpIdAndCovenantMasterId("CP-1", "1"))
                .thenReturn(Optional.empty());

        service.recommendForCp("CP-1");

        ArgumentCaptor<CovenantRecommendation> cap =
                ArgumentCaptor.forClass(CovenantRecommendation.class);
        verify(recommendationRepo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(RecommendationStatus.SUGGESTED);
    }

    @Test
    void rerunningPreservesAcceptedStatus() {
        CovenantMaster cm = newCovenant("1", List.of("RETAIL"));
        when(attributesService.fetch("CP-1")).thenReturn(
                new CovenantAttributes("CP-1", "RETAIL", "S", Map.of()));
        when(masterRepo.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(cm));
        CovenantRecommendation existing = new CovenantRecommendation();
        existing.setCpId("CP-1");
        existing.setCovenantMasterId("1");
        existing.setStatus(RecommendationStatus.ACCEPTED);
        existing.setMatchReason("old reason");
        when(recommendationRepo.findByCpIdAndCovenantMasterId("CP-1", "1"))
                .thenReturn(Optional.of(existing));

        service.recommendForCp("CP-1");

        ArgumentCaptor<CovenantRecommendation> cap =
                ArgumentCaptor.forClass(CovenantRecommendation.class);
        verify(recommendationRepo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(RecommendationStatus.ACCEPTED);
    }

    @Test
    void markAcceptedCreatesNewWhenAbsent() {
        when(recommendationRepo.findByCpIdAndCovenantMasterId("CP-1", "9"))
                .thenReturn(Optional.empty());
        service.markAcceptedOrManual("CP-1", "9", false);
        ArgumentCaptor<CovenantRecommendation> cap =
                ArgumentCaptor.forClass(CovenantRecommendation.class);
        verify(recommendationRepo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(RecommendationStatus.MANUAL_ADD);
    }

    @Test
    void markAcceptedFromRecommendationUpdatesExisting() {
        CovenantRecommendation existing = new CovenantRecommendation();
        existing.setCpId("CP-1");
        existing.setCovenantMasterId("9");
        existing.setStatus(RecommendationStatus.SUGGESTED);
        when(recommendationRepo.findByCpIdAndCovenantMasterId("CP-1", "9"))
                .thenReturn(Optional.of(existing));
        service.markAcceptedOrManual("CP-1", "9", true);
        ArgumentCaptor<CovenantRecommendation> cap =
                ArgumentCaptor.forClass(CovenantRecommendation.class);
        verify(recommendationRepo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(RecommendationStatus.ACCEPTED);
    }

    @Test
    void markRejectedFlipsExisting() {
        CovenantRecommendation existing = new CovenantRecommendation();
        existing.setCpId("CP-1");
        existing.setCovenantMasterId("9");
        existing.setStatus(RecommendationStatus.SUGGESTED);
        when(recommendationRepo.findByCpIdAndCovenantMasterId("CP-1", "9"))
                .thenReturn(Optional.of(existing));
        service.markRejected("CP-1", "9");
        ArgumentCaptor<CovenantRecommendation> cap =
                ArgumentCaptor.forClass(CovenantRecommendation.class);
        verify(recommendationRepo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(RecommendationStatus.REJECTED);
    }

    @Test
    void markRejectedAbsentIsNoOp() {
        when(recommendationRepo.findByCpIdAndCovenantMasterId("CP-X", "9"))
                .thenReturn(Optional.empty());
        service.markRejected("CP-X", "9");
        verify(recommendationRepo, never()).save(any());
    }

    @Test
    void historyForReturnsAllRows() {
        when(recommendationRepo.findByCpId("CP-1")).thenReturn(List.of());
        assertThat(service.historyFor("CP-1")).isEmpty();
        verify(recommendationRepo, times(1)).findByCpId("CP-1");
    }

    @Test
    void cpWithoutMatchingSegmentSkipped() {
        CovenantMaster cm = newCovenant("1", List.of("SME"));
        when(attributesService.fetch(eq("CP-1"))).thenReturn(
                new CovenantAttributes("CP-1", "RETAIL", "S", Map.of()));
        when(masterRepo.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(cm));

        var result = service.recommendForCp("CP-1");

        assertThat(result).isEmpty();
        verify(recommendationRepo, never()).save(any());
    }

    private static CovenantMaster newCovenant(String id, List<String> segments) {
        CovenantMaster cm = new CovenantMaster();
        try {
            Field f = CovenantMaster.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(cm, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        cm.setName("Cov " + id);
        cm.setCategory(CovenantCategory.FINANCIAL);
        cm.setConditionExpression("DSCR > 1");
        cm.setBusinessSegments(segments);
        cm.setActive(true);
        return cm;
    }
}
