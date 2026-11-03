package com.crisil.covenant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.ops.CpCleanupRequest;
import com.crisil.covenant.dto.ops.CpCleanupResult;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.service.CpDataCleanupService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for the confirm-to-delete guard on the destructive cleanup endpoint: a matching
 * {@code confirmCpId} delegates to the service, while a mismatch, a null id, or a missing body is
 * rejected with {@code 400 CP_CONFIRM_MISMATCH} before the service is ever touched.
 */
@ExtendWith(MockitoExtension.class)
class OpsCleanupControllerTest {

    @Mock private CpDataCleanupService service;
    @InjectMocks private OpsCleanupController controller;

    @Test
    void matchingConfirmIdDelegatesToService() {
        CpCleanupResult expected = new CpCleanupResult("CP-1", Map.of("cov_applied_covenant", 2L),
                java.util.List.of());
        when(service.cleanupCp("CP-1")).thenReturn(expected);

        CpCleanupResult actual = controller.cleanup("CP-1", new CpCleanupRequest("CP-1"));

        assertThat(actual).isSameAs(expected);
        verify(service).cleanupCp("CP-1");
    }

    @Test
    void mismatchedConfirmIdIsRejectedWithoutTouchingService() {
        assertThatThrownBy(() -> controller.cleanup("CP-1", new CpCleanupRequest("CP-2")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getCode()).isEqualTo("CP_CONFIRM_MISMATCH");
                });

        verify(service, never()).cleanupCp(anyString());
    }

    @Test
    void nullConfirmIdIsRejected() {
        assertThatThrownBy(() -> controller.cleanup("CP-1", new CpCleanupRequest(null)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("CP_CONFIRM_MISMATCH"));

        verify(service, never()).cleanupCp(anyString());
    }

    @Test
    void missingBodyIsRejected() {
        assertThatThrownBy(() -> controller.cleanup("CP-1", null))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getCode()).isEqualTo("CP_CONFIRM_MISMATCH");
                });

        verify(service, never()).cleanupCp(anyString());
    }
}
