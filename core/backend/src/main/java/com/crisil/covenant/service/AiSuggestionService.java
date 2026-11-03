package com.crisil.covenant.service;

import com.crisil.covenant.dto.AiStatusRecommendation;
import com.crisil.covenant.dto.AiStatusRequest;
import com.crisil.covenant.service.AttachmentService;
import com.crisil.covenant.service.ReviewService;
import com.crisil.covenant.dto.AttachmentView;
import com.crisil.covenant.dto.CpReviewRow;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.repository.CovenantMasterRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Assembles the AI status-suggestion request for a CP from the assessment grid and the uploaded
 * fileIds (US-MON-18 AC1), calls the resilient {@link AiStatusRecommender}, and returns only the
 * recommendations that map to a covenant currently on the CP. Never throws — an unavailable AI
 * service yields an empty list (US-MON-18 AC6).
 */
@Service
public class AiSuggestionService {

    private final ReviewService reviewService;
    private final AttachmentService attachmentService;
    private final CovenantMasterRepository masterRepo;
    private final AiStatusRecommender recommender;

    public AiSuggestionService(ReviewService reviewService,
                               AttachmentService attachmentService,
                               CovenantMasterRepository masterRepo,
                               AiStatusRecommender recommender) {
        this.reviewService = reviewService;
        this.attachmentService = attachmentService;
        this.masterRepo = masterRepo;
        this.recommender = recommender;
    }

    /** Per-covenant AI recommendations for a CP (US-MON-18), filtered to covenants on the CP. */
    public List<AiStatusRecommendation> suggestForCp(String cpId) {
        List<CpReviewRow> rows = reviewService.reviewRows(cpId);
        List<String> certificateFileIds = attachmentService.certificatesForCp(cpId).stream()
                .map(AttachmentView::fileId).toList();

        List<String> masterIds = rows.stream()
                .map(CpReviewRow::covenantMasterId).filter(Objects::nonNull).distinct().toList();
        Map<String, String> descriptionByMaster = new HashMap<>();
        for (CovenantMaster m : masterRepo.findAllById(masterIds)) {
            descriptionByMaster.put(m.getId(), m.getDescription());
        }

        List<AiStatusRequest.Covenant> covenants = rows.stream()
                .map(r -> new AiStatusRequest.Covenant(
                        r.appliedCovenantId(), r.covenantName(),
                        descriptionByMaster.get(r.covenantMasterId()),
                        r.conditionExpression(),
                        r.computedValue(), r.latestResult(),
                        r.attachments().stream().map(AttachmentView::fileId).toList()))
                .toList();

        Set<String> knownCovenants = rows.stream()
                .map(CpReviewRow::appliedCovenantId).collect(Collectors.toSet());
        return recommender.recommend(new AiStatusRequest(cpId, certificateFileIds, covenants)).stream()
                .filter(Objects::nonNull)
                .filter(rec -> rec.appliedCovenantId() != null
                        && knownCovenants.contains(rec.appliedCovenantId()))
                .toList();
    }
}
