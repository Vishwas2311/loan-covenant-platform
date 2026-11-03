package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.AiStatusRecommendation;
import com.crisil.covenant.dto.AiStatusRequest;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.service.AttachmentService;
import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.enums.CovenantStatusSource;
import com.crisil.covenant.service.ReviewService;
import com.crisil.covenant.dto.AttachmentView;
import com.crisil.covenant.dto.CpReviewRow;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.repository.CovenantMasterRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * US-MON-18: assembles the AI request from the grid + uploaded fileIds and returns only the
 * recommendations that map to a covenant currently on the CP; an unavailable AI service (empty
 * result from the resilient recommender) yields an empty list (AC6).
 */
@ExtendWith(MockitoExtension.class)
class AiSuggestionServiceTest {

    @Mock private ReviewService reviewService;
    @Mock private AttachmentService attachmentService;
    @Mock private CovenantMasterRepository masterRepo;
    @Mock private AiStatusRecommender recommender;

    private AiSuggestionService service() {
        return new AiSuggestionService(reviewService, attachmentService, masterRepo, recommender);
    }

    @Test
    void buildsRequestFromGridAndFilesAndFiltersUnknownCovenants() {
        when(reviewService.reviewRows("CP-1")).thenReturn(List.of(
                row("1", view("a1")), row("2")));
        when(attachmentService.certificatesForCp("CP-1")).thenReturn(List.of(view("cert1")));
        List<CovenantMaster> masters = List.of(master("50", "Debt service coverage floor"));
        when(masterRepo.findAllById(any())).thenReturn(masters);
        when(recommender.recommend(any())).thenReturn(List.of(
                rec("1", CovenantDispositionStatus.WAIVER),
                rec("2", CovenantDispositionStatus.EXTENSION),
                rec("99", CovenantDispositionStatus.WAIVER))); // unknown covenant

        List<AiStatusRecommendation> out = service().suggestForCp("CP-1");

        assertThat(out).extracting(AiStatusRecommendation::appliedCovenantId)
                .containsExactly("1", "2");

        ArgumentCaptor<AiStatusRequest> req = ArgumentCaptor.forClass(AiStatusRequest.class);
        verify(recommender).recommend(req.capture());
        assertThat(req.getValue().cpId()).isEqualTo("CP-1");
        assertThat(req.getValue().certificateFileIds()).containsExactly("cert1");
        assertThat(req.getValue().covenants()).hasSize(2);
        assertThat(req.getValue().covenants().get(0).attachmentFileIds()).containsExactly("a1");
        assertThat(req.getValue().covenants().get(0).covenantDescription())
                .isEqualTo("Debt service coverage floor");
    }

    @Test
    void unavailableAiServiceYieldsEmptyList() {
        when(reviewService.reviewRows("CP-1")).thenReturn(List.of(row("1")));
        when(attachmentService.certificatesForCp("CP-1")).thenReturn(List.of());
        List<CovenantMaster> masters = List.of(master("50", "desc"));
        when(masterRepo.findAllById(any())).thenReturn(masters);
        when(recommender.recommend(any())).thenReturn(List.of());

        assertThat(service().suggestForCp("CP-1")).isEmpty();
    }

    @Test
    void nullEntriesAndNullCovenantIdsAreFilteredOut() {
        when(reviewService.reviewRows("CP-1")).thenReturn(List.of(row("1")));
        when(attachmentService.certificatesForCp("CP-1")).thenReturn(List.of());
        List<CovenantMaster> masters = List.of(master("50", "desc"));
        when(masterRepo.findAllById(any())).thenReturn(masters);
        when(recommender.recommend(any())).thenReturn(Arrays.asList(
                null,
                rec(null, CovenantDispositionStatus.WAIVER),
                rec("1", CovenantDispositionStatus.WAIVER)));

        assertThat(service().suggestForCp("CP-1"))
                .extracting(AiStatusRecommendation::appliedCovenantId)
                .containsExactly("1");
    }

    private static AiStatusRecommendation rec(String appliedId, CovenantDispositionStatus status) {
        return new AiStatusRecommendation(appliedId, status, "rationale", 0.75);
    }

    private static CovenantMaster master(String id, String description) {
        CovenantMaster m = org.mockito.Mockito.mock(CovenantMaster.class);
        org.mockito.Mockito.lenient().when(m.getId()).thenReturn(id);
        org.mockito.Mockito.lenient().when(m.getDescription()).thenReturn(description);
        return m;
    }

    private static AttachmentView view(String fileId) {
        return new AttachmentView("1", fileId, fileId + ".pdf", "rm", Instant.now());
    }

    private static CpReviewRow row(String appliedId, AttachmentView... attachments) {
        return new CpReviewRow(appliedId, "50", "Cov-" + appliedId, "DSCR > 1.2",
                CovenantCategory.FINANCIAL, AssessmentType.AUTO, "1.0", "Double", null,
                AssessmentResult.BREACHED, "100", Instant.now(),
                CovenantDispositionStatus.BREACHED, null, CovenantStatusSource.SYSTEM, null, true,
                LocalDate.now(), LocalDate.now(), null, 0L, List.of(attachments));
    }
}
