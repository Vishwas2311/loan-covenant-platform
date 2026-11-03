package com.crisil.covenant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.ConversionResult;
import com.crisil.covenant.dto.FxRateRequest;
import com.crisil.covenant.service.FxService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FxControllerTest {

    @Mock FxService service;
    @InjectMocks FxController controller;

    @Test
    void delegates() {
        LocalDate d = LocalDate.of(2026, 3, 31);
        when(service.rates()).thenReturn(List.of());
        assertThat(controller.rates()).isEmpty();
        FxRateRequest req = new FxRateRequest("USD", "INR", d, BigDecimal.TEN, null);
        controller.upsert(req);
        verify(service).upsert(req);
        assertThat(controller.delete("r1").getStatusCode().value()).isEqualTo(204);
        ConversionResult r = new ConversionResult(BigDecimal.ONE, "USD", BigDecimal.TEN, "INR", BigDecimal.TEN, d,
                false);
        when(service.toReporting(BigDecimal.ONE, "USD", d)).thenReturn(r);
        when(service.convert(BigDecimal.ONE, "USD", "EUR", d)).thenReturn(r);
        assertThat(controller.convert(BigDecimal.ONE, "USD", null, d)).isSameAs(r);
        assertThat(controller.convert(BigDecimal.ONE, "USD", " ", d)).isSameAs(r);
        assertThat(controller.convert(BigDecimal.ONE, "USD", "EUR", d)).isSameAs(r);
    }
}
