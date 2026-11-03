package com.crisil.covenant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.ops.ReingestRequest;
import com.crisil.covenant.dto.ops.RemediationResult;
import com.crisil.covenant.service.OpsRemediationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OpsRemediationControllerTest {

    @Mock private OpsRemediationService service;
    @InjectMocks private OpsRemediationController controller;

    @Test
    void reingestWithBodyPassesTableId() {
        RemediationResult r = result("reingest");
        when(service.reingestCp("CP-1", "tblA")).thenReturn(r);

        assertThat(controller.reingest("CP-1", new ReingestRequest("tblA"))).isSameAs(r);
        verify(service).reingestCp("CP-1", "tblA");
    }

    @Test
    void reingestWithNullBodyPassesNullTableId() {
        RemediationResult r = result("reingest");
        when(service.reingestCp("CP-1", null)).thenReturn(r);

        assertThat(controller.reingest("CP-1", null)).isSameAs(r);
        verify(service).reingestCp("CP-1", null);
    }

    @Test
    void clearIngestionStateDelegates() {
        RemediationResult r = result("clear-ingestion-state");
        when(service.clearIngestionState("CP-1", "tblA")).thenReturn(r);

        assertThat(controller.clearIngestionState("CP-1", "tblA")).isSameAs(r);
        verify(service).clearIngestionState("CP-1", "tblA");
    }

    @Test
    void reassessDelegates() {
        RemediationResult r = result("reassess");
        when(service.reassess("ac1")).thenReturn(r);

        assertThat(controller.reassess("ac1")).isSameAs(r);
        verify(service).reassess("ac1");
    }

    private static RemediationResult result(String action) {
        return new RemediationResult(action, "CP-1", null, List.of(), false, null, List.of());
    }
}
