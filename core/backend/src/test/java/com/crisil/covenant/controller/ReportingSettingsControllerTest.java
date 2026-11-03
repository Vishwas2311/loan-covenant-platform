package com.crisil.covenant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.ReportingSettingsResponse;
import com.crisil.covenant.dto.UpdateReportingSettingsRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.crisil.covenant.service.ReportingSettingsService;

@ExtendWith(MockitoExtension.class)
class ReportingSettingsControllerTest {

    @Mock private ReportingSettingsService service;
    @InjectMocks private ReportingSettingsController controller;

    @Test
    void getReturnsTheCurrentPolicy() {
        ReportingSettingsResponse resp = new ReportingSettingsResponse(4, false, 7, 30, true, "INR", null, null);
        when(service.current()).thenReturn(resp);

        assertThat(controller.get()).isSameAs(resp);
    }

    @Test
    void updateDelegatesToTheService() {
        UpdateReportingSettingsRequest req = new UpdateReportingSettingsRequest(4, false, 7, 30);
        ReportingSettingsResponse resp = new ReportingSettingsResponse(4, false, 7, 30, true, "INR", null, null);
        when(service.update(req)).thenReturn(resp);

        assertThat(controller.update(req)).isSameAs(resp);
    }
}
