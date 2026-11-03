package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.crisil.covenant.domain.CreditProposalDetails;
import com.crisil.covenant.dto.AttachmentView;
import com.crisil.covenant.dto.CpCycleView;
import com.crisil.covenant.dto.CpReviewRow;
import com.crisil.covenant.dto.CycleReviewResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/** US-MON-23: cycle listing + guarded, read-only cycle review assembly. */
@ExtendWith(MockitoExtension.class)
class CpCycleServiceTest {

    @Mock private CpLineageService lineageService;
    @Mock private ReviewService reviewService;
    @Mock private AttachmentService attachmentService;
    @Mock private CreditProposalClient creditProposalClient;

    private CpCycleService service() {
        return new CpCycleService(lineageService, reviewService, attachmentService, creditProposalClient);
    }

    private static CreditProposalDetails named(String name) {
        return new CreditProposalDetails("CP", name, null, null, null, null, null, null, null, null);
    }

    @Test
    void cyclesForDelegatesToLineage() {
        List<CpCycleView> lineage = List.of(
                new CpCycleView(1, "CP-1003", false),
                new CpCycleView(2, "CP-1001", true));
        when(lineageService.lineageFor("CP-1001")).thenReturn(lineage);

        assertThat(service().cyclesFor("CP-1001")).isSameAs(lineage);
    }

    @Test
    void cycleReviewForInLineageIdReturnsRowsCertsNameAndCycleNumber() {
        when(lineageService.lineageFor("CP-1001")).thenReturn(List.of(
                new CpCycleView(1, "CP-1003", false),
                new CpCycleView(2, "CP-1002", false),
                new CpCycleView(3, "CP-1001", true)));
        List<CpReviewRow> rows = new ArrayList<>(); // distinct instance ⇒ identity pass-through is meaningful
        List<AttachmentView> certs =
                List.of(new AttachmentView("a1", "f1", "cert.pdf", "u", Instant.now()));
        when(reviewService.reviewRows("CP-1002")).thenReturn(rows);
        when(attachmentService.certificatesForCp("CP-1002")).thenReturn(certs);
        when(creditProposalClient.lookup("CP-1002")).thenReturn(named("Reliance Industries Ltd"));

        CycleReviewResponse out = service().cycleReview("CP-1001", "CP-1002");

        assertThat(out.cpId()).isEqualTo("CP-1002");
        assertThat(out.cycleNumber()).isEqualTo(2);
        assertThat(out.cpName()).isEqualTo("Reliance Industries Ltd");
        assertThat(out.review()).isSameAs(rows);
        assertThat(out.certificates()).extracting(AttachmentView::fileId).containsExactly("f1");
    }

    @Test
    void cycleReviewRejectsOutOfLineageIdWith404() {
        when(lineageService.lineageFor("CP-1001"))
                .thenReturn(List.of(new CpCycleView(1, "CP-1001", true)));

        assertThatThrownBy(() -> service().cycleReview("CP-1001", "CP-9999"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void cycleReviewNullNameLookupYieldsNullName() {
        when(lineageService.lineageFor("CP-1001"))
                .thenReturn(List.of(new CpCycleView(1, "CP-1001", true)));
        when(reviewService.reviewRows("CP-1001")).thenReturn(new ArrayList<>());
        when(attachmentService.certificatesForCp("CP-1001")).thenReturn(List.of());
        when(creditProposalClient.lookup("CP-1001")).thenReturn(null);

        assertThat(service().cycleReview("CP-1001", "CP-1001").cpName()).isNull();
    }

    @Test
    void cycleReviewNameLookupFailureYieldsNullName() {
        when(lineageService.lineageFor("CP-1001"))
                .thenReturn(List.of(new CpCycleView(1, "CP-1001", true)));
        when(reviewService.reviewRows("CP-1001")).thenReturn(new ArrayList<>());
        when(attachmentService.certificatesForCp("CP-1001")).thenReturn(List.of());
        when(creditProposalClient.lookup("CP-1001")).thenThrow(new RuntimeException("CP service down"));

        assertThat(service().cycleReview("CP-1001", "CP-1001").cpName()).isNull();
    }
}
