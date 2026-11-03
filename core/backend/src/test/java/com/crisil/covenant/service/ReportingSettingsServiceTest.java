package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.dto.ReportingSettingsResponse;
import com.crisil.covenant.dto.UpdateReportingSettingsRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import com.crisil.covenant.entity.ReportingSetting;
import com.crisil.covenant.repository.ReportingSettingRepository;

@ExtendWith(MockitoExtension.class)
class ReportingSettingsServiceTest {

    @Mock private ReportingSettingRepository repository;
    @InjectMocks private ReportingSettingsService service;

    @Test
    void currentReturnsThePersistedRowWhenPresent() {
        ReportingSetting row = new ReportingSetting();
        row.setFiscalYearStartMonth(4);
        row.setPeriodicIncludesCurrentPeriod(false);
        when(repository.findAll()).thenReturn(List.of(row));

        ReportingSettingsResponse r = service.current();

        assertThat(r.fiscalYearStartMonth()).isEqualTo(4);
        assertThat(r.periodicIncludesCurrentPeriod()).isFalse();
    }

    @Test
    void currentFallsBackToDefaultsWhenNoRowExists() {
        when(repository.findAll()).thenReturn(List.of());

        ReportingSettingsResponse r = service.current();

        assertThat(r.fiscalYearStartMonth()).isEqualTo(1);
        assertThat(r.periodicIncludesCurrentPeriod()).isTrue();
        assertThat(r.modifiedDate()).isNull();
        assertThat(r.modifiedBy()).isNull();
    }

    @Test
    void updateUpsertsTheExistingSingletonRow() {
        ReportingSetting row = new ReportingSetting();
        when(repository.findAll()).thenReturn(List.of(row));
        when(repository.save(any(ReportingSetting.class))).thenAnswer(inv -> inv.getArgument(0));

        ReportingSettingsResponse r = service.update(new UpdateReportingSettingsRequest(4, false, 10, 45));

        assertThat(row.getFiscalYearStartMonth()).isEqualTo(4);
        assertThat(row.isPeriodicIncludesCurrentPeriod()).isFalse();
        assertThat(row.getDueSoonDays()).isEqualTo(10);
        assertThat(row.getUpcomingWindowDays()).isEqualTo(45);
        assertThat(r.fiscalYearStartMonth()).isEqualTo(4);
        assertThat(r.periodicIncludesCurrentPeriod()).isFalse();
        assertThat(r.dueSoonDays()).isEqualTo(10);
        assertThat(r.upcomingWindowDays()).isEqualTo(45);
        verify(repository).save(row);
    }

    @Test
    void updateCreatesARowWhenNoneHasBeenPersisted() {
        when(repository.findAll()).thenReturn(List.of());
        when(repository.save(any(ReportingSetting.class))).thenAnswer(inv -> inv.getArgument(0));

        ReportingSettingsResponse r = service.update(new UpdateReportingSettingsRequest(7, true, 7, 30));

        assertThat(r.fiscalYearStartMonth()).isEqualTo(7);
        assertThat(r.periodicIncludesCurrentPeriod()).isTrue();
        verify(repository).save(any(ReportingSetting.class));
    }

    @Test
    void updateRejectsAFiscalMonthBelowOne() {
        assertThatThrownBy(() -> service.update(new UpdateReportingSettingsRequest(0, true, 7, 30)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getCode()).isEqualTo("INVALID_FISCAL_MONTH");
                });
        verify(repository, never()).save(any());
    }

    @Test
    void updateRejectsAFiscalMonthAboveTwelve() {
        assertThatThrownBy(() -> service.update(new UpdateReportingSettingsRequest(13, true, 7, 30)))
                .isInstanceOf(ApiException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void startingSoonDaysMappingIsRetainedForSchemaCompatibility() {
        // The "Starting soon" window was retired from the settings API, but the starting_soon_days
        // column (V6) and its entity mapping are kept so ddl-auto:validate still matches the released
        // schema. This pins that retained accessor; nothing in the API path touches it any more.
        ReportingSetting row = new ReportingSetting();
        assertThat(row.getStartingSoonDays()).isEqualTo(14);
        row.setStartingSoonDays(21);
        assertThat(row.getStartingSoonDays()).isEqualTo(21);
    }
}
