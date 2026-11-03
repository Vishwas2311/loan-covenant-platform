package com.crisil.covenant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.PeriodComparison;
import com.crisil.covenant.security.CpAccessGuard;
import com.crisil.covenant.service.ComparisonService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExportControllerTest {

    @Mock ComparisonService service;
    @Mock CpAccessGuard guard;
    @InjectMocks ExportController controller;

    @Test
    void delegatesAndServesXlsx() {
        PeriodComparison cmp = new PeriodComparison("CP-1", List.of(), List.of());
        when(service.compare("CP-1", 4)).thenReturn(cmp);
        assertThat(controller.comparison("CP-1", 4)).isSameAs(cmp);

        when(service.compareXlsx("CP-1", 8)).thenReturn(new byte[] {1});
        var file = controller.comparisonXlsx("CP-1", 8);
        assertThat(file.getHeaders().getContentType()).isEqualTo(ExportController.XLSX);
        assertThat(file.getHeaders().getContentDisposition().getFilename()).isEqualTo("covenants-CP-1.xlsx");

        when(service.libraryXlsx()).thenReturn(new byte[] {2});
        assertThat(controller.libraryXlsx().getBody()).containsExactly(2);
        verify(guard, org.mockito.Mockito.times(2)).requireCp("CP-1");
    }
}
