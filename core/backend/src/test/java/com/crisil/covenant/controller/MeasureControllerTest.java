package com.crisil.covenant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.formula.FunctionKind;
import com.crisil.covenant.dto.CreateMeasureRequest;
import com.crisil.covenant.dto.MeasureResponse;
import com.crisil.covenant.dto.UpdateMeasureRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.crisil.covenant.service.MeasureService;

@ExtendWith(MockitoExtension.class)
class MeasureControllerTest {

    @Mock private MeasureService service;
    @InjectMocks private MeasureController controller;

    private MeasureResponse sample(String id) {
        return new MeasureResponse(id, "PAT", "Profit After Tax", "d", "5", "7", "cp_financials",
                "pat_cr", null, FunctionKind.AVG, DataColumnType.NUMBER, "Cr", true,
                Instant.now(), "system", Instant.now(), "system", "0");
    }

    private CreateMeasureRequest createReq() {
        return new CreateMeasureRequest("PAT", "Profit After Tax", "d", "5", "pat_cr",
                FunctionKind.AVG, DataColumnType.NUMBER, "Cr", null);
    }

    @Test
    void createReturns201WithLocation() {
        when(service.create(createReq())).thenReturn(sample("7"));
        ResponseEntity<MeasureResponse> resp = controller.create(createReq());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getHeaders().getLocation()).hasToString("/api/v1/measures/7");
        assertThat(resp.getBody()).isNotNull();
    }

    @Test
    void updateDelegates() {
        UpdateMeasureRequest req = new UpdateMeasureRequest("PAT", "n", "d", "5", "pat_cr",
                FunctionKind.AVG, DataColumnType.NUMBER, "Cr", null);
        when(service.update("3", req)).thenReturn(sample("3"));
        assertThat(controller.update("3", req).id()).isEqualTo("3");
    }

    @Test
    void deactivateDelegates() {
        when(service.setActive("3", false)).thenReturn(sample("3"));
        controller.deactivate("3");
        verify(service).setActive("3", false);
    }

    @Test
    void getDelegates() {
        when(service.get("3")).thenReturn(sample("3"));
        assertThat(controller.get("3").id()).isEqualTo("3");
    }

    @Test
    void listDelegates() {
        when(service.list(true)).thenReturn(List.of(sample("1")));
        assertThat(controller.list(true)).hasSize(1);
    }
}
