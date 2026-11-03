package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.AttachmentView;
import com.crisil.covenant.dto.CertificateRequirementRequest;
import com.crisil.covenant.dto.CertificateRequirementView;
import com.crisil.covenant.dto.CertificateRequirementView.State;
import com.crisil.covenant.entity.CertificateRequirement;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.repository.CertificateRequirementRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CertificateRequirementServiceTest {

    private static final LocalDate Q1 = LocalDate.of(2026, 3, 31);
    private static final LocalDate Q2 = LocalDate.of(2026, 6, 30);
    private static final LocalDate Q3 = LocalDate.of(2026, 9, 30);
    private static final Clock TODAY = Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC);

    @Mock private CertificateRequirementRepository repo;
    @Mock private AttachmentService attachmentService;

    private CertificateRequirementService service() {
        return new CertificateRequirementService(repo, attachmentService, TODAY);
    }

    @Test
    void stateIsDerivedFromCertificatesAndDueDate() {
        when(repo.findByCpIdOrderByPeriodEndAsc("CP-1")).thenReturn(List.of(
                new CertificateRequirement("CP-1", Q1, LocalDate.of(2026, 5, 15), null),
                new CertificateRequirement("CP-1", Q2, LocalDate.of(2026, 8, 14), "late"),
                new CertificateRequirement("CP-1", Q3, LocalDate.of(2026, 11, 14), null)));
        AttachmentView q1Cert = new AttachmentView("c1", "f1", "q1.pdf", "rm", Instant.now(), Q1);
        AttachmentView general = new AttachmentView("c2", "f2", "g.pdf", "rm", Instant.now());
        when(attachmentService.certificatesForCp("CP-1")).thenReturn(List.of(q1Cert, general));

        List<CertificateRequirementView> out = service().forCp("CP-1");

        assertThat(out).extracting(CertificateRequirementView::state)
                .containsExactly(State.RECEIVED, State.OVERDUE, State.PENDING);
        assertThat(out.get(0).certificates()).containsExactly(q1Cert);
        assertThat(out.get(1).overdueDays()).isEqualTo(80);
        assertThat(out.get(2).overdueDays()).isZero();
    }

    @Test
    void upsertCreatesOrUpdatesThePeriod() {
        when(repo.findByCpIdAndPeriodEnd("CP-1", Q3)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(attachmentService.certificatesForCp("CP-1")).thenReturn(List.of());

        CertificateRequirementView created = service().upsert("CP-1",
                new CertificateRequirementRequest(Q3, LocalDate.of(2026, 11, 14), "Q3"));
        assertThat(created.state()).isEqualTo(State.PENDING);
        assertThat(created.note()).isEqualTo("Q3");

        CertificateRequirement existing = new CertificateRequirement("CP-1", Q3, LocalDate.of(2026, 11, 14), null);
        when(repo.findByCpIdAndPeriodEnd("CP-1", Q3)).thenReturn(Optional.of(existing));
        CertificateRequirementView moved = service().upsert("CP-1",
                new CertificateRequirementRequest(Q3, LocalDate.of(2026, 10, 1), "moved"));
        assertThat(moved.dueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(moved.state()).isEqualTo(State.OVERDUE);
    }

    @Test
    void dueDateBeforePeriodEndIsRejected() {
        assertThatThrownBy(() -> service().upsert("CP-1",
                new CertificateRequirementRequest(Q3, Q2, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("due date");
        verify(repo, never()).save(any());
    }

    @Test
    void removeIsScopedToTheCp() {
        CertificateRequirement r = new CertificateRequirement("CP-1", Q1, Q2, null);
        when(repo.findByIdAndCpId("r1", "CP-1")).thenReturn(Optional.of(r));
        service().remove("CP-1", "r1");
        verify(repo).delete(r);
        when(repo.findByIdAndCpId("r2", "CP-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().remove("CP-1", "r2")).isInstanceOf(ApiException.class);
    }
}
