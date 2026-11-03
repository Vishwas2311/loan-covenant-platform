package com.crisil.covenant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.crisil.covenant.service.BreachReportService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportControllerTest {

    @Mock BreachReportService service;
    @InjectMocks ReportController controller;

    @Test
    void delegatesAndClampsNegativeThreshold() {
        when(service.openBreaches(any(), eq(0))).thenReturn(List.of());
        assertThat(controller.openBreaches(-5)).isEmpty();
        when(service.openBreachesXlsx(any(), eq(14))).thenReturn(new byte[] {1});
        var file = controller.openBreachesXlsx(14);
        assertThat(file.getHeaders().getContentDisposition().getFilename()).isEqualTo("open-breaches.xlsx");
        when(service.openBreachesPdf(any(), eq(3))).thenReturn(new byte[] {2});
        assertThat(controller.openBreachesPdf(3).getHeaders().getContentType().toString()).isEqualTo("application/pdf");
    }
}
