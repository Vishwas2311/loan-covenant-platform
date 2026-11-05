package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.ConversionResult;
import com.crisil.covenant.dto.FxRateRequest;
import com.crisil.covenant.entity.FxRate;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.repository.FxRateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FxServiceTest {

    private static final LocalDate D = LocalDate.of(2026, 3, 31);

    @Mock private FxRateRepository repo;
    @Mock private ReportingSettingsService settings;
    @Mock private OpsAuditService audit;

    private FxService service() {
        return new FxService(repo, settings, audit);
    }

    @Test
    void sameCurrencyIsUnchanged() {
        ConversionResult r = service().convert(new BigDecimal("10"), "INR", "INR", D);
        assertThat(r.converted()).isEqualByComparingTo("10");
        assertThat(r.rateDate()).isNull();
    }

    @Test
    void usesDirectRateThenInverse() {
        when(repo.findFirstByFromCurrencyAndToCurrencyAndRateDateLessThanEqualOrderByRateDateDesc("USD", "INR", D))
                .thenReturn(Optional.of(new FxRate("USD", "INR", D.minusDays(1), new BigDecimal("83.5"), "RBI")));
        ConversionResult direct = service().convert(new BigDecimal("2"), "USD", "INR", D);
        assertThat(direct.converted()).isEqualByComparingTo("167");
        assertThat(direct.inverse()).isFalse();
        assertThat(direct.rateDate()).isEqualTo(D.minusDays(1));

        when(repo.findFirstByFromCurrencyAndToCurrencyAndRateDateLessThanEqualOrderByRateDateDesc("INR", "USD", D))
                .thenReturn(Optional.empty());
        ConversionResult inverse = service().convert(new BigDecimal("167"), "INR", "USD", D);
        assertThat(inverse.converted()).isEqualByComparingTo("2");
        assertThat(inverse.inverse()).isTrue();
    }

    @Test
    void missingRateIs422AndReportingCurrencyComesFromSettings() {
        when(settings.reportingCurrency()).thenReturn("INR");
        when(repo.findFirstByFromCurrencyAndToCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().toReporting(BigDecimal.ONE, "EUR", D))
                .isInstanceOf(ApiException.class).hasMessageContaining("EUR/INR");
    }

    @Test
    void upsertListAndDelete() {
        FxRateRequest req = new FxRateRequest("USD", "INR", D, new BigDecimal("83"), "RBI");
        when(repo.findByFromCurrencyAndToCurrencyAndRateDate("USD", "INR", D)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(service().upsert(req).rate()).isEqualByComparingTo("83");

        FxRate existing = new FxRate("USD", "INR", D, new BigDecimal("80"), null);
        when(repo.findByFromCurrencyAndToCurrencyAndRateDate("USD", "INR", D)).thenReturn(Optional.of(existing));
        assertThat(service().upsert(req).source()).isEqualTo("RBI");
        assertThat(existing.getRate()).isEqualByComparingTo("83");

        assertThatThrownBy(() -> service().upsert(new FxRateRequest("INR", "INR", D, BigDecimal.ONE, null)))
                .isInstanceOf(ApiException.class);

        when(repo.findAllByOrderByRateDateDescFromCurrencyAsc()).thenReturn(List.of(existing));
        assertThat(service().rates()).hasSize(1);

        when(repo.findById("r1")).thenReturn(Optional.of(existing));
        service().delete("r1");
        verify(repo).delete(existing);
        verify(audit).recordSuccess(eq("DELETE_FX_RATE"), eq("FX_RATE"), eq("r1"), contains("/"), anyString());
        when(repo.findById("r2")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().delete("r2")).isInstanceOf(ApiException.class);
        verifyNoMoreInteractions(audit);
    }
}
