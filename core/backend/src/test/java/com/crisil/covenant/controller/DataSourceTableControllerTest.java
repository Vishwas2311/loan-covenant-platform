package com.crisil.covenant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.CreateDataSourceTableRequest;
import com.crisil.covenant.dto.DataSourceTableResponse;
import com.crisil.covenant.dto.UpdateDataSourceTableRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.crisil.covenant.domain.DataColumn;
import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.enums.TableEntityKeyLevel;
import com.crisil.covenant.enums.TableLayout;
import com.crisil.covenant.service.DataSourceTableService;

@ExtendWith(MockitoExtension.class)
class DataSourceTableControllerTest {

    @Mock private DataSourceTableService service;
    @InjectMocks private DataSourceTableController controller;

    private DataSourceTableResponse sample(String id) {
        return new DataSourceTableResponse(id, "1", "Financials", "public", "cp_financials_demo",
                "cp_id", "as_of_date", TableLayout.COLUMNAR, null, null, TableEntityKeyLevel.CP_ID, true,
                Instant.now(), "system", Instant.now(), "system", "0");
    }

    private CreateDataSourceTableRequest createReq() {
        return new CreateDataSourceTableRequest("Financials", "public", "cp_financials_demo",
                "cp_id", "as_of_date", null, null, null, null);
    }

    @Test
    void createReturns201WithLocation() {
        when(service.create("1", createReq())).thenReturn(sample("2"));
        ResponseEntity<DataSourceTableResponse> resp = controller.create("1", createReq());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getHeaders().getLocation()).hasToString("/api/v1/data-sources/1/tables/2");
    }

    @Test
    void updateDelegates() {
        UpdateDataSourceTableRequest req = new UpdateDataSourceTableRequest(
                "Financials", "public", "cp_financials_demo", "cp_id", "as_of_date", null, null, null, null);
        when(service.update("1", "2", req)).thenReturn(sample("2"));
        assertThat(controller.update("1", "2", req).id()).isEqualTo("2");
    }

    @Test
    void deactivateDelegates() {
        when(service.setActive("1", "2", false)).thenReturn(sample("2"));
        controller.deactivate("1", "2");
        verify(service).setActive("1", "2", false);
    }

    @Test
    void getDelegates() {
        when(service.get("1", "2")).thenReturn(sample("2"));
        assertThat(controller.get("1", "2").id()).isEqualTo("2");
    }

    @Test
    void listDelegates() {
        when(service.list("1")).thenReturn(List.of(sample("2")));
        assertThat(controller.list("1")).hasSize(1);
    }

    @Test
    void columnsDelegates() {
        when(service.columns("1", "2")).thenReturn(List.of(
                DataColumn.of("dscr", "dscr", DataColumnType.NUMBER)));
        assertThat(controller.columns("1", "2")).hasSize(1);
    }

    @Test
    void codeValuesDelegates() {
        when(service.codeValues("1", "2")).thenReturn(List.of("DSCR", "PAT"));
        assertThat(controller.codeValues("1", "2")).containsExactly("DSCR", "PAT");
    }
}
