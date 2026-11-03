package com.crisil.covenant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.DataEventRequest;
import com.crisil.covenant.dto.DataEventResponse;
import com.crisil.covenant.enums.TableEntityKeyLevel;
import com.crisil.covenant.service.DataEventService;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DataEventControllerTest {

    @Mock DataEventService service;
    @InjectMocks DataEventController controller;

    @Test
    void returnsAcceptedWithResult() {
        DataEventRequest req = new DataEventRequest("ds", "CP-1", TableEntityKeyLevel.CP_ID,
                LocalDate.of(2026, 3, 31), Instant.parse("2026-04-12T10:00:00Z"), null);
        when(service.handle(req)).thenReturn(new DataEventResponse(2, false));
        var resp = controller.receive(req);
        assertThat(resp.getStatusCode().value()).isEqualTo(202);
        assertThat(resp.getBody()).isEqualTo(new DataEventResponse(2, false));
    }
}
