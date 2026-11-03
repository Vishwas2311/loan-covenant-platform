package com.crisil.covenant.service;

import com.crisil.covenant.domain.CreditProposalDetails;
import com.crisil.covenant.dto.AttachmentView;
import com.crisil.covenant.dto.CpCycleView;
import com.crisil.covenant.dto.CpReviewRow;
import com.crisil.covenant.dto.CycleReviewResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-MON-23: serves the read-only cycle-history viewer. It lists the opened CP's renewal lineage
 * (delegating to {@link CpLineageService}) and, for one cycle CP in that lineage, assembles the same
 * read model the live assessment grid shows — the US-MON-16 review rows (which already embed
 * per-covenant attachments) plus the US-MON-17 CP compliance certificates.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}: {@link #cyclesFor(String)} and the
 * {@code cpName} enrichment can make external CP-service calls, and we must not pin a DB connection
 * across remote I/O. The membership guard in {@link #cycleReview(String, String)} is the only new
 * access control — the underlying review read is already un-scoped (see {@link ReviewService}).
 */
@Service
public class CpCycleService {

    private static final Logger LOG = LoggerFactory.getLogger(CpCycleService.class);

    private final CpLineageService lineageService;
    private final ReviewService reviewService;
    private final AttachmentService attachmentService;
    private final CreditProposalClient creditProposalClient;

    public CpCycleService(CpLineageService lineageService,
                          ReviewService reviewService,
                          AttachmentService attachmentService,
                          CreditProposalClient creditProposalClient) {
        this.lineageService = lineageService;
        this.reviewService = reviewService;
        this.attachmentService = attachmentService;
        this.creditProposalClient = creditProposalClient;
    }

    /** The opened CP's lineage as cycles (US-MON-23 AC2); a CP with no parent yields a single cycle. */
    public List<CpCycleView> cyclesFor(String cpId) {
        return lineageService.lineageFor(cpId);
    }

    /**
     * The read-only assessment view of {@code cycleCpId} within {@code entryCpId}'s lineage
     * (US-MON-23 AC3/AC4/AC5). A single lineage walk both enforces the membership guard (AC8) and
     * resolves the cycle number: a {@code cycleCpId} outside the lineage yields {@code 404} rather
     * than exposing an unrelated CP's data.
     */
    public CycleReviewResponse cycleReview(String entryCpId, String cycleCpId) {
        CpCycleView node = lineageService.lineageFor(entryCpId).stream()
                .filter(c -> c.cpId().equals(cycleCpId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Cycle " + cycleCpId + " is not in the lineage of " + entryCpId));
        List<CpReviewRow> review = reviewService.reviewRows(cycleCpId);
        List<AttachmentView> certificates = attachmentService.certificatesForCp(cycleCpId);
        return new CycleReviewResponse(cycleCpId, node.cycleNumber(), cpName(cycleCpId),
                review, certificates);
    }

    /** Best-effort display name for a cycle CP; a failed/absent lookup simply yields {@code null}. */
    private String cpName(String cpId) {
        try {
            CreditProposalDetails details = creditProposalClient.lookup(cpId);
            return details == null ? null : details.name();
        } catch (RuntimeException ex) {
            LOG.warn("CP name lookup failed for {}: {}",
                    String.valueOf(cpId).replace('\n', '_').replace('\r', '_'),
                    String.valueOf(ex.getMessage()).replace('\n', '_').replace('\r', '_'));
            return null;
        }
    }
}
