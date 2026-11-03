package com.crisil.covenant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.CertificateRequirementRequest;
import com.crisil.covenant.dto.CertificateRequirementView;
import com.crisil.covenant.security.CpAccessGuard;
import com.crisil.covenant.service.CertificateRequirementService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CertificateRequirementControllerTest {

    @Mock CertificateRequirementService service;
    @Mock CpAccessGuard guard;
    @InjectMocks CertificateRequirementController controller;

    @Test
    void delegatesWithCpGuard() {
        List<CertificateRequirementView> views = List.of();
        when(service.forCp("CP-1")).thenReturn(views);
        assertThat(controller.list("CP-1")).isSameAs(views);

        CertificateRequirementRequest req = new CertificateRequirementRequest(
                LocalDate.of(2026, 3, 31), LocalDate.of(2026, 5, 15), null);
        controller.upsert("CP-1", req);
        verify(service).upsert("CP-1", req);

        assertThat(controller.remove("CP-1", "r1").getStatusCode().value()).isEqualTo(204);
        verify(service).remove("CP-1", "r1");
        verify(guard, org.mockito.Mockito.times(3)).requireCp("CP-1");
    }
}
