package com.crisil.covenant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.CreateDataSourceRequest;
import com.crisil.covenant.dto.DataSourceResponse;
import com.crisil.covenant.dto.TestConnectionResult;
import com.crisil.covenant.dto.UpdateDataSourceRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.crisil.covenant.domain.DataColumn;
import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.enums.DataSourceType;
import com.crisil.covenant.service.DataSourceService;

@ExtendWith(MockitoExtension.class)
class DataSourceControllerTest {

    @Mock private DataSourceService service;
    @InjectMocks private DataSourceController controller;

    private DataSourceResponse sample(String id) {
        return new DataSourceResponse(id, "REST", DataSourceType.REST, "d",
                Map.of("valuesPath", "/v"), true, Instant.now(), "system", null, null, "0");
    }

    @Test
    void createReturns201WithLocation() {
        CreateDataSourceRequest req =
                new CreateDataSourceRequest("REST", DataSourceType.REST, "d", Map.of());
        when(service.create(req)).thenReturn(sample("7"));

        ResponseEntity<DataSourceResponse> resp = controller.create(req);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getHeaders().getLocation()).hasToString("/api/v1/data-sources/7");
        assertThat(resp.getBody()).isNotNull();
    }

    @Test
    void updateDelegates() {
        UpdateDataSourceRequest req =
                new UpdateDataSourceRequest("REST", DataSourceType.REST, "d", Map.of());
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

    @Test
    void columnsDelegates() {
        when(service.columns("3")).thenReturn(List.of(
                DataColumn.of("DSCR", "DSCR", DataColumnType.NUMBER)));
        assertThat(controller.columns("3")).hasSize(1);
    }

    @Test
    void testConnectionDelegates() {
        when(service.testConnection(eq("3"))).thenReturn(TestConnectionResult.ok(4));
        assertThat(controller.testConnection("3").columnCount()).isEqualTo(4);
    }
}
