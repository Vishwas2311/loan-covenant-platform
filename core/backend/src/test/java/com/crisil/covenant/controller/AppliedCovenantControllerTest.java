package com.crisil.covenant.controller;

import com.crisil.covenant.security.CpAccessGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.AppliedCovenantResponse;
import com.crisil.covenant.dto.ApplyCovenantRequest;
import com.crisil.covenant.dto.BulkApplyCovenantsRequest;
import com.crisil.covenant.dto.DefinitionChangeRow;
import com.crisil.covenant.dto.UpdateAppliedCovenantRequest;
import com.crisil.covenant.entity.AppliedCovenantAudit;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.enums.ThresholdSource;
import com.crisil.covenant.service.AppliedCovenantService;
import com.crisil.covenant.service.CachedCovenantAttributesService;
import com.crisil.covenant.service.TransientRetry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link AppliedCovenantController}: every endpoint forwards to
 * {@link AppliedCovenantService} and returns its result unchanged.
 */
@ExtendWith(MockitoExtension.class)
class AppliedCovenantControllerTest {

    @Mock private AppliedCovenantService service;
    @Mock private CpAccessGuard guard;
    @Spy private TransientRetry retry = new TransientRetry();
    @Mock private CachedCovenantAttributesService cpCache;
    @InjectMocks private AppliedCovenantController controller;

    @Test
    void applyReturns201WithTheCreatedRows() {
        BulkApplyCovenantsRequest request = new BulkApplyCovenantsRequest(List.of(
                new ApplyCovenantRequest("1", Frequency.DAILY, null, null)));
        List<AppliedCovenantResponse> created = List.of(response("100", null));
        when(service.applyBulk("CP-1", request)).thenReturn(created);

        var result = controller.apply("CP-1", request);

        assertThat(result.getStatusCode().value()).isEqualTo(201);
        assertThat(result.getBody()).isSameAs(created);
        verify(cpCache).ensureCached("CP-1");
    }

    @Test
    void listDelegatesToTheService() {
        List<AppliedCovenantResponse> rows = List.of(response("100", null));
        when(service.listForCp("CP-1")).thenReturn(rows);

        assertThat(controller.list("CP-1")).isSameAs(rows);
        verify(service).listForCp("CP-1");
    }

    @Test
    void updateDelegatesToTheService() {
        UpdateAppliedCovenantRequest request =
                new UpdateAppliedCovenantRequest(Frequency.WEEKLY, null, null);
        AppliedCovenantResponse updated = response("100", null);
        when(service.update("100", request)).thenReturn(updated);

        assertThat(controller.update("100", request)).isSameAs(updated);
        verify(service).update("100", request);
    }

    @Test
    void auditDelegatesToTheService() {
        List<AppliedCovenantAudit> trail = List.of(new AppliedCovenantAudit(
                "100", AppliedCovenantAudit.ChangeType.FREQUENCY, "DAILY", "WEEKLY",
                Instant.parse("2026-07-25T10:15:30Z"), "alice"));
        when(service.auditFor("100")).thenReturn(trail);

        assertThat(controller.audit("100")).isSameAs(trail);
        verify(service).auditFor("100");
    }

    /** US-CM-25 AC7: the per-CP definition change log, newest first. */
    @Test
    void definitionHistoryDelegatesToTheService() {
        List<DefinitionChangeRow> rows = List.of(new DefinitionChangeRow(
                Instant.parse("2026-07-25T10:15:30Z"), "alice",
                "LATEST(DSCR) >= 1.5", "LATEST(ICR) >= 2.0 AND LATEST(DSCR) >= 1.3",
                ThresholdSource.USER));
        when(service.definitionHistory("100")).thenReturn(rows);

        assertThat(controller.definitionHistory("100")).isSameAs(rows);
        verify(service).definitionHistory("100");
    }

    @Test
    void definitionHistoryReturnsEmptyWhenNoOverrideWasCommitted() {
        when(service.definitionHistory("101")).thenReturn(List.of());

        assertThat(controller.definitionHistory("101")).isEmpty();
    }

    private static AppliedCovenantResponse response(String id, String override) {
        return new AppliedCovenantResponse(
                id, "CP-1", "1", "Master 1", Frequency.DAILY, Frequency.DAILY.defaultCron(),
                LocalDate.now(), null, AppliedCovenantStatus.ACTIVE, "{}",
                override, override != null ? override : "DSCR > 1",
                null, null, ThresholdSource.MASTER);
    }
}
