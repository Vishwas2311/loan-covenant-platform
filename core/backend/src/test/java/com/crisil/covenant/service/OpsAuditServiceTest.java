package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.dto.ops.OpsAuditView;
import com.crisil.covenant.entity.OpsAudit;
import com.crisil.covenant.enums.OpsAuditOutcome;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.repository.OpsAuditRepository;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
class OpsAuditServiceTest {

    @Mock
    private OpsAuditRepository repository;

    @InjectMocks
    private OpsAuditService service;

    @AfterEach
    void clearAttrs() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void withUser(String user) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(CurrentUserContext.USER_HEADER, user);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private OpsAudit captureSaved() {
        ArgumentCaptor<OpsAudit> cap = ArgumentCaptor.forClass(OpsAudit.class);
        org.mockito.Mockito.verify(repository).save(cap.capture());
        return cap.getValue();
    }

    private static OpsAudit auditRow(String id, String action, String targetType, String targetId,
                                     OpsAuditOutcome outcome) {
        OpsAudit row = new OpsAudit();
        ReflectionTestUtils.setField(row, "id", id);
        row.setAction(action);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setPerformedBy("alice");
        row.setParamsSummary("tableId=T1");
        row.setOutcome(outcome);
        row.setDetail("done");
        row.setPerformedAt(Instant.parse("2026-07-25T10:15:30Z"));
        return row;
    }

    @Test
    void recordSuccessPersistsOutcomeAndResolvesUser() {
        when(repository.save(any(OpsAudit.class))).thenAnswer(inv -> inv.getArgument(0));
        withUser("alice");

        service.recordSuccess("reingest", "cp", "CP-1", "tableId=T1", "ingested 1 table");

        OpsAudit saved = captureSaved();
        assertThat(saved.getAction()).isEqualTo("reingest");
        assertThat(saved.getTargetType()).isEqualTo("cp");
        assertThat(saved.getTargetId()).isEqualTo("CP-1");
        assertThat(saved.getPerformedBy()).isEqualTo("alice");
        assertThat(saved.getParamsSummary()).isEqualTo("tableId=T1");
        assertThat(saved.getOutcome()).isEqualTo(OpsAuditOutcome.SUCCESS);
        assertThat(saved.getDetail()).isEqualTo("ingested 1 table");
        assertThat(saved.getPerformedAt()).isNotNull();
    }

    @Test
    void recordFailurePersistsFailureOutcome() {
        when(repository.save(any(OpsAudit.class))).thenAnswer(inv -> inv.getArgument(0));

        service.recordFailure("cleanup", "cp", "CP-9", null, "rolled back");

        OpsAudit saved = captureSaved();
        assertThat(saved.getOutcome()).isEqualTo(OpsAuditOutcome.FAILURE);
        assertThat(saved.getParamsSummary()).isNull();
        assertThat(saved.getDetail()).isEqualTo("rolled back");
    }

    @Test
    void fallsBackToSystemWhenNoUserHeader() {
        when(repository.save(any(OpsAudit.class))).thenAnswer(inv -> inv.getArgument(0));

        service.recordSuccess("reassess", "applied_covenant", "AC-1", null, null);

        assertThat(captureSaved().getPerformedBy()).isEqualTo("system");
    }

    @Test
    void sanitisesCarriageReturnsAndNewlinesInAllTextFields() {
        when(repository.save(any(OpsAudit.class))).thenAnswer(inv -> inv.getArgument(0));
        withUser("ev\nil\ruser");

        service.recordSuccess("act\nion", "cp", "CP\r-1", "a=1\nb=2", "line1\r\nline2");

        OpsAudit saved = captureSaved();
        assertThat(saved.getPerformedBy()).isEqualTo("ev_il_user");
        assertThat(saved.getAction()).isEqualTo("act_ion");
        assertThat(saved.getTargetId()).isEqualTo("CP_-1");
        assertThat(saved.getParamsSummary()).isEqualTo("a=1_b=2");
        assertThat(saved.getDetail()).isEqualTo("line1__line2");
    }

    @Test
    void clampsOverlongParamsAndTargetId() {
        when(repository.save(any(OpsAudit.class))).thenAnswer(inv -> inv.getArgument(0));
        String longParams = "x".repeat(2500);
        String longTarget = "y".repeat(200);

        service.recordSuccess("reingest", "cp", longTarget, longParams, null);

        OpsAudit saved = captureSaved();
        assertThat(saved.getParamsSummary()).hasSize(2000);
        assertThat(saved.getTargetId()).hasSize(128);
        assertThat(saved.getDetail()).isNull();
    }

    @Test
    void writeMethodsRunInNewTransactionSoTheySurviveCallerRollback() throws NoSuchMethodException {
        for (String name : new String[] {"recordSuccess", "recordFailure"}) {
            Method m = OpsAuditService.class.getMethod(
                    name, String.class, String.class, String.class, String.class, String.class);
            Transactional tx = m.getAnnotation(Transactional.class);
            assertThat(tx).as("%s must be @Transactional", name).isNotNull();
            assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        }
    }

    @Test
    void trailWithTargetIdReadsTheTargetScopedFinderNewestFirst() {
        OpsAudit newer = auditRow("a2", "cleanup", "cp", "CP-1", OpsAuditOutcome.FAILURE);
        OpsAudit older = auditRow("a1", "reingest", "cp", "CP-1", OpsAuditOutcome.SUCCESS);
        when(repository.findByTargetTypeAndTargetIdOrderByPerformedAtDesc("cp", "CP-1"))
                .thenReturn(List.of(newer, older));

        List<OpsAuditView> views = service.trail("cp", "CP-1");

        assertThat(views).hasSize(2);
        OpsAuditView first = views.get(0);
        assertThat(first.id()).isEqualTo("a2");
        assertThat(first.action()).isEqualTo("cleanup");
        assertThat(first.targetType()).isEqualTo("cp");
        assertThat(first.targetId()).isEqualTo("CP-1");
        assertThat(first.performedBy()).isEqualTo("alice");
        assertThat(first.outcome()).isEqualTo("FAILURE");
        assertThat(first.performedAt()).isEqualTo(Instant.parse("2026-07-25T10:15:30Z"));
        assertThat(views.get(1).outcome()).isEqualTo("SUCCESS");
    }

    @Test
    void trailWithoutTargetIdReadsTheTypeOnlyFinder() {
        OpsAudit row = auditRow("a1", "reingest", "cp", "CP-1", OpsAuditOutcome.SUCCESS);
        when(repository.findByTargetTypeOrderByPerformedAtDesc("cp")).thenReturn(List.of(row));

        List<OpsAuditView> views = service.trail("cp", null);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).id()).isEqualTo("a1");
        assertThat(views.get(0).outcome()).isEqualTo("SUCCESS");
    }

    @Test
    void trailTreatsBlankTargetIdAsTypeOnly() {
        when(repository.findByTargetTypeOrderByPerformedAtDesc("cp")).thenReturn(List.of());

        assertThat(service.trail("cp", "   ")).isEmpty();
        verify(repository).findByTargetTypeOrderByPerformedAtDesc("cp");
    }

    @Test
    void trailRequiresTargetType() {
        for (String bad : new String[] {null, "  "}) {
            assertThatThrownBy(() -> service.trail(bad, "CP-1"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException api = (ApiException) ex;
                        assertThat(api.getCode()).isEqualTo("OPS_AUDIT_TARGET_TYPE_REQUIRED");
                        assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    });
        }
        verifyNoInteractions(repository);
    }
}
