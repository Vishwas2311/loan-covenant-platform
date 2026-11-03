package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.repository.AssessmentRepository;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.repository.CovenantMasterRepository;
import com.crisil.covenant.domain.CovenantOnCp;
import com.crisil.covenant.service.MonitoringService;
import com.crisil.covenant.entity.CovenantStatusAudit;
import com.crisil.covenant.repository.CovenantStatusAuditRepository;
import com.crisil.covenant.dto.CpReviewRow;
import com.crisil.covenant.dto.DispositionDecisionRequest;
import com.crisil.covenant.dto.SetDispositionRequest;
import com.crisil.covenant.enums.DispositionApproval;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import com.crisil.covenant.dto.StatusAuditView;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;
import com.crisil.covenant.entity.CovenantAttachment;
import com.crisil.covenant.entity.CovenantDisposition;
import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.enums.CovenantStatusSource;
import com.crisil.covenant.repository.CovenantAttachmentRepository;
import com.crisil.covenant.repository.CovenantDispositionRepository;

/**
 * US-MON-16/19: derives the CP assessment grid from stored results (headroom, Complied-lock,
 * Breached-dispositionable, MANUAL Actual/Headroom hiding), records Waiver/Extension dispositions with
 * the EXTENSION-date rule, and appends an audit row per change (USER vs AI). Uses a real
 * {@link FormulaEngine} so headroom is genuinely computed.
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock private MonitoringService monitoringService;
    @Mock private AssessmentRepository assessmentRepo;
    @Mock private CovenantMasterRepository masterRepo;
    @Mock private CovenantDispositionRepository dispositionRepo;
    @Mock private CovenantStatusAuditRepository auditRepo;
    @Mock private CovenantAttachmentRepository attachmentRepo;
    @Mock private ReportingSettingsService settingsService;

    /** Existing scenarios run with maker-checker off; the FR-12 tests switch it on. */
    @BeforeEach
    void approvalOff() {
        lenient().when(settingsService.dispositionApprovalRequired()).thenReturn(false);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private ReviewService service() {
        return new ReviewService(monitoringService, assessmentRepo, masterRepo,
                dispositionRepo, auditRepo, attachmentRepo, new FormulaEngine(), settingsService,
                Clock.systemUTC());
    }

    // -- reviewRows ------------------------------------------------------

    @Test
    void financialCompliantRowComputesHeadroomAndLocksStatus() {
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.COMPLIANT, "100")));
        when(masterRepo.findAllById(any())).thenReturn(List.of(master("50", CovenantCategory.FINANCIAL)));
        when(assessmentRepo.findAllById(any())).thenReturn(List.of(assessment("100", "1.5")));

        CpReviewRow row = service().reviewRows("CP-1").get(0);

        assertThat(row.computedValue()).isEqualTo("1.5");
        assertThat(row.headroom()).isEqualByComparingTo("0.3"); // actual − threshold (lower bound)
        assertThat(row.dispositionStatus()).isEqualTo(CovenantDispositionStatus.COMPLIED);
        assertThat(row.statusSource()).isEqualTo(CovenantStatusSource.SYSTEM);
        assertThat(row.dispositionable()).isFalse();
    }

    /**
     * US-CM-24 AC9: a MANUAL covenant has no machine-computed value — a person verifies it — so the grid
     * shows no Actual and no Headroom for it.
     */
    @Test
    void manualRowHidesActualAndHeadroom() {
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.COMPLIANT, "100")));
        when(masterRepo.findAllById(any())).thenReturn(List.of(
                master("50", CovenantCategory.NON_FINANCIAL, AssessmentType.MANUAL)));

        CpReviewRow row = service().reviewRows("CP-1").get(0);

        assertThat(row.assessmentType()).isEqualTo(AssessmentType.MANUAL);
        assertThat(row.computedValue()).isNull();
        assertThat(row.computedValueType()).isNull();
        assertThat(row.headroom()).isNull();
    }

    /**
     * A MANUAL covenant is hidden on its <em>type</em>, not its category: classifying one FINANCIAL — as
     * the covenant library screen allows, since Category and Assessment type are chosen independently —
     * must not make the grid promise an Actual that no engine ever computed.
     */
    @Test
    void financialManualRowStillHidesActualAndHeadroom() {
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.COMPLIANT, "100")));
        when(masterRepo.findAllById(any())).thenReturn(List.of(
                master("50", CovenantCategory.FINANCIAL, AssessmentType.MANUAL)));

        CpReviewRow row = service().reviewRows("CP-1").get(0);

        assertThat(row.category()).isEqualTo(CovenantCategory.FINANCIAL);
        assertThat(row.computedValue()).isNull();
        assertThat(row.headroom()).isNull();
    }

    /**
     * The bug this rule replaces: an AUTO covenant classified NON_FINANCIAL <em>is</em> evaluated, so its
     * Actual and Headroom must reach the grid. The old category gate discarded both.
     */
    @Test
    void nonFinancialAutoRowStillShowsActualAndHeadroom() {
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.COMPLIANT, "100")));
        when(masterRepo.findAllById(any())).thenReturn(List.of(
                master("50", CovenantCategory.NON_FINANCIAL, AssessmentType.AUTO)));
        when(assessmentRepo.findAllById(any())).thenReturn(List.of(assessment("100", "1.5")));

        CpReviewRow row = service().reviewRows("CP-1").get(0);

        assertThat(row.category()).isEqualTo(CovenantCategory.NON_FINANCIAL);
        assertThat(row.assessmentType()).isEqualTo(AssessmentType.AUTO);
        assertThat(row.computedValue()).isEqualTo("1.5");
        assertThat(row.headroom()).isEqualByComparingTo("0.3");
    }

    /** A row whose master can no longer be loaded is treated as AUTO, so nothing computed is lost. */
    @Test
    void rowWithNoLoadableMasterKeepsItsComputedValue() {
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.COMPLIANT, "100")));
        when(masterRepo.findAllById(any())).thenReturn(List.of());
        when(assessmentRepo.findAllById(any())).thenReturn(List.of(assessment("100", "1.5")));

        CpReviewRow row = service().reviewRows("CP-1").get(0);

        assertThat(row.category()).isNull();
        assertThat(row.assessmentType()).isEqualTo(AssessmentType.AUTO);
        assertThat(row.computedValue()).isEqualTo("1.5");
    }

    @Test
    void breachedRowIsDispositionable() {
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.BREACHED, "100")));
        when(masterRepo.findAllById(any())).thenReturn(List.of(master("50", CovenantCategory.FINANCIAL)));
        when(assessmentRepo.findAllById(any())).thenReturn(List.of(assessment("100", "1.0")));

        CpReviewRow row = service().reviewRows("CP-1").get(0);

        assertThat(row.dispositionStatus()).isEqualTo(CovenantDispositionStatus.BREACHED);
        assertThat(row.dispositionable()).isTrue();
        assertThat(row.headroom()).isEqualByComparingTo("-0.2"); // breach size
    }

    @Test
    void dispositionAddressingCurrentBreachIsShown() {
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.BREACHED, "100")));
        when(masterRepo.findAllById(any())).thenReturn(List.of(master("50", CovenantCategory.FINANCIAL)));
        when(dispositionRepo.findByCpId("CP-1")).thenReturn(List.of(
                disposition("1", "100", CovenantDispositionStatus.WAIVER, CovenantStatusSource.USER, null)));

        CpReviewRow row = service().reviewRows("CP-1").get(0);

        assertThat(row.dispositionStatus()).isEqualTo(CovenantDispositionStatus.WAIVER);
        assertThat(row.statusSource()).isEqualTo(CovenantStatusSource.USER);
        assertThat(row.dispositionable()).isTrue();
    }

    @Test
    void staleDispositionForOldAssessmentIsIgnored() {
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.BREACHED, "200")));
        when(masterRepo.findAllById(any())).thenReturn(List.of(master("50", CovenantCategory.FINANCIAL)));
        when(dispositionRepo.findByCpId("CP-1")).thenReturn(List.of(
                disposition("1", "100", CovenantDispositionStatus.WAIVER, CovenantStatusSource.USER, null)));

        CpReviewRow row = service().reviewRows("CP-1").get(0);

        // disposition addressed assessment 100, current breach is 200 ⇒ derived BREACHED
        assertThat(row.dispositionStatus()).isEqualTo(CovenantDispositionStatus.BREACHED);
        assertThat(row.statusSource()).isEqualTo(CovenantStatusSource.SYSTEM);
    }

    @Test
    void attachmentsAreMappedOntoTheRow() {
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.BREACHED, "100")));
        when(masterRepo.findAllById(any())).thenReturn(List.of(master("50", CovenantCategory.FINANCIAL)));
        when(attachmentRepo.findByCpIdOrderByUploadedAtDesc("CP-1")).thenReturn(List.of(
                new CovenantAttachment("CP-1", "1", "f1", "evidence.pdf", "rm", Instant.now())));

        CpReviewRow row = service().reviewRows("CP-1").get(0);

        assertThat(row.attachments()).singleElement()
                .satisfies(a -> assertThat(a.fileId()).isEqualTo("f1"));
    }

    @Test
    void extensionDispositionAddressingCurrentBreachIsShown() {
        LocalDate due = LocalDate.now().plusDays(30);
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.BREACHED, "100")));
        when(masterRepo.findAllById(any())).thenReturn(List.of(master("50", CovenantCategory.FINANCIAL)));
        when(dispositionRepo.findByCpId("CP-1")).thenReturn(List.of(
                disposition("1", "100", CovenantDispositionStatus.EXTENSION, CovenantStatusSource.USER, due)));

        CpReviewRow row = service().reviewRows("CP-1").get(0);

        assertThat(row.dispositionStatus()).isEqualTo(CovenantDispositionStatus.EXTENSION);
        assertThat(row.extensionDate()).isEqualTo(due);
    }

    @Test
    void dispositionWithNoAssessmentIdIsIgnored() {
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.BREACHED, "100")));
        when(masterRepo.findAllById(any())).thenReturn(List.of(master("50", CovenantCategory.FINANCIAL)));
        when(dispositionRepo.findByCpId("CP-1")).thenReturn(List.of(
                disposition("1", null, CovenantDispositionStatus.WAIVER, CovenantStatusSource.USER, null)));

        CpReviewRow row = service().reviewRows("CP-1").get(0);

        assertThat(row.dispositionStatus()).isEqualTo(CovenantDispositionStatus.BREACHED);
    }

    // -- setDisposition --------------------------------------------------

    @Test
    void setWaiverPersistsDispositionAndAudits() {
        stubBreachedLatest("1", "100", "CP-1");
        when(dispositionRepo.findByCpIdAndAppliedCovenantId("CP-1", "1")).thenReturn(Optional.empty());
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.BREACHED, null)));

        CpReviewRow row = service().setDisposition("CP-1", "1",
                new SetDispositionRequest(CovenantDispositionStatus.WAIVER, null, "waived", null));

        assertThat(row.appliedCovenantId()).isEqualTo("1");
        ArgumentCaptor<CovenantDisposition> disp = ArgumentCaptor.forClass(CovenantDisposition.class);
        verify(dispositionRepo).save(disp.capture());
        assertThat(disp.getValue().getStatus()).isEqualTo(CovenantDispositionStatus.WAIVER);
        assertThat(disp.getValue().getExtensionDate()).isNull();
        assertThat(disp.getValue().getStatusSource()).isEqualTo(CovenantStatusSource.USER);
        assertThat(disp.getValue().getAssessmentId()).isEqualTo("100");

        ArgumentCaptor<CovenantStatusAudit> audit = ArgumentCaptor.forClass(CovenantStatusAudit.class);
        verify(auditRepo).save(audit.capture());
        assertThat(audit.getValue().getOldStatus()).isEqualTo(CovenantDispositionStatus.BREACHED);
        assertThat(audit.getValue().getNewStatus()).isEqualTo(CovenantDispositionStatus.WAIVER);
        assertThat(audit.getValue().getSource()).isEqualTo(CovenantStatusSource.USER);
        assertThat(audit.getValue().getChangedBy()).isNotNull();
    }

    @Test
    void setExtensionWithDatePersistsDate() {
        LocalDate due = LocalDate.now().plusDays(30);
        stubBreachedLatest("1", "100", "CP-1");
        when(dispositionRepo.findByCpIdAndAppliedCovenantId("CP-1", "1")).thenReturn(Optional.empty());
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.BREACHED, null)));

        service().setDisposition("CP-1", "1",
                new SetDispositionRequest(CovenantDispositionStatus.EXTENSION, due, null, null));

        ArgumentCaptor<CovenantDisposition> disp = ArgumentCaptor.forClass(CovenantDisposition.class);
        verify(dispositionRepo).save(disp.capture());
        assertThat(disp.getValue().getStatus()).isEqualTo(CovenantDispositionStatus.EXTENSION);
        assertThat(disp.getValue().getExtensionDate()).isEqualTo(due);
        ArgumentCaptor<CovenantStatusAudit> audit = ArgumentCaptor.forClass(CovenantStatusAudit.class);
        verify(auditRepo).save(audit.capture());
        assertThat(audit.getValue().getNewExtensionDate()).isEqualTo(due);
    }

    @Test
    void applyingAiSuggestionAuditsWithAiSource() {
        stubBreachedLatest("1", "100", "CP-1");
        when(dispositionRepo.findByCpIdAndAppliedCovenantId("CP-1", "1")).thenReturn(Optional.empty());
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.BREACHED, null)));

        service().setDisposition("CP-1", "1", new SetDispositionRequest(
                CovenantDispositionStatus.WAIVER, null, "AI: cured next quarter", CovenantStatusSource.AI));

        ArgumentCaptor<CovenantStatusAudit> audit = ArgumentCaptor.forClass(CovenantStatusAudit.class);
        verify(auditRepo).save(audit.capture());
        assertThat(audit.getValue().getSource()).isEqualTo(CovenantStatusSource.AI);
    }

    @Test
    void reDispositioningSameBreachCarriesOldStatus() {
        stubBreachedLatest("1", "100", "CP-1");
        CovenantDisposition existing =
                disposition("1", "100", CovenantDispositionStatus.WAIVER, CovenantStatusSource.USER, null);
        setId(existing, "9");
        when(dispositionRepo.findByCpIdAndAppliedCovenantId("CP-1", "1")).thenReturn(Optional.of(existing));
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.BREACHED, null)));

        service().setDisposition("CP-1", "1", new SetDispositionRequest(
                CovenantDispositionStatus.EXTENSION, LocalDate.now().plusDays(10), null, null));

        ArgumentCaptor<CovenantStatusAudit> audit = ArgumentCaptor.forClass(CovenantStatusAudit.class);
        verify(auditRepo).save(audit.capture());
        assertThat(audit.getValue().getOldStatus()).isEqualTo(CovenantDispositionStatus.WAIVER);
        assertThat(audit.getValue().getNewStatus()).isEqualTo(CovenantDispositionStatus.EXTENSION);
    }

    @Test
    void reDispositioningStaleBreachResetsOldStatusToBreached() {
        stubBreachedLatest("1", "100", "CP-1");
        CovenantDisposition existing =
                disposition("1", "50", CovenantDispositionStatus.WAIVER, CovenantStatusSource.USER, null);
        setId(existing, "9"); // has an id but addresses OLD assessment 50, not the current breach 100
        when(dispositionRepo.findByCpIdAndAppliedCovenantId("CP-1", "1")).thenReturn(Optional.of(existing));
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.BREACHED, null)));

        service().setDisposition("CP-1", "1",
                new SetDispositionRequest(CovenantDispositionStatus.WAIVER, null, null, null));

        ArgumentCaptor<CovenantStatusAudit> audit = ArgumentCaptor.forClass(CovenantStatusAudit.class);
        verify(auditRepo).save(audit.capture());
        assertThat(audit.getValue().getOldStatus()).isEqualTo(CovenantDispositionStatus.BREACHED);
    }

    @Test
    void extensionWithoutDateIsRejected() {
        assertThatThrownBy(() -> service().setDisposition("CP-1", "1",
                new SetDispositionRequest(CovenantDispositionStatus.EXTENSION, null, null, null)))
                .satisfies(t -> assertStatus(t, 400));
        verify(dispositionRepo, never()).save(any());
    }

    @Test
    void nonWaiverExtensionStatusIsRejected() {
        assertThatThrownBy(() -> service().setDisposition("CP-1", "1",
                new SetDispositionRequest(CovenantDispositionStatus.COMPLIED, null, null, null)))
                .satisfies(t -> assertStatus(t, 400));
    }

    @Test
    void dispositionOnNonBreachedResultIsConflict() {
        stubLatest("1", "100", "CP-1", AssessmentResult.COMPLIANT);

        assertThatThrownBy(() -> service().setDisposition("CP-1", "1",
                new SetDispositionRequest(CovenantDispositionStatus.WAIVER, null, null, null)))
                .satisfies(t -> assertStatus(t, 409));
    }

    @Test
    void dispositionWhenNeverAssessedIsNotFound() {
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(Page.empty());

        assertThatThrownBy(() -> service().setDisposition("CP-1", "1",
                new SetDispositionRequest(CovenantDispositionStatus.WAIVER, null, null, null)))
                .satisfies(t -> assertStatus(t, 404));
    }

    @Test
    void dispositionForMismatchedCpIsNotFound() {
        stubBreachedLatest("1", "100", "CP-OTHER");

        assertThatThrownBy(() -> service().setDisposition("CP-1", "1",
                new SetDispositionRequest(CovenantDispositionStatus.WAIVER, null, null, null)))
                .satisfies(t -> assertStatus(t, 404));
    }

    // -- audit trails ----------------------------------------------------

    @Test
    void auditForCpMapsRowsToViews() {
        when(auditRepo.findByCpIdOrderByChangedDateDesc("CP-1")).thenReturn(List.of(audit("CP-1", "1")));
        List<StatusAuditView> out = service().auditForCp("CP-1");
        assertThat(out).singleElement().satisfies(v -> {
            assertThat(v.cpId()).isEqualTo("CP-1");
            assertThat(v.newStatus()).isEqualTo(CovenantDispositionStatus.WAIVER);
        });
    }

    @Test
    void auditForCovenantMapsRowsToViews() {
        when(auditRepo.findByAppliedCovenantIdOrderByChangedDateDesc("1")).thenReturn(List.of(audit("CP-1", "1")));
        assertThat(service().auditForCovenant("1")).singleElement()
                .satisfies(v -> assertThat(v.appliedCovenantId()).isEqualTo("1"));
    }

    // -- FR-12 maker-checker ---------------------------------------------

    @Test
    void withApprovalOnTheDispositionIsRequestedAndNotAudited() {
        when(settingsService.dispositionApprovalRequired()).thenReturn(true);
        stubBreachedLatest("1", "100", "CP-1");
        when(dispositionRepo.findByCpIdAndAppliedCovenantId("CP-1", "1")).thenReturn(Optional.empty());
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.BREACHED, null)));
        as("rm-anil", "RM");

        service().setDisposition("CP-1", "1",
                new SetDispositionRequest(CovenantDispositionStatus.WAIVER, null, "one-off", null));

        ArgumentCaptor<CovenantDisposition> disp = ArgumentCaptor.forClass(CovenantDisposition.class);
        verify(dispositionRepo).save(disp.capture());
        assertThat(disp.getValue().getApprovalStatus()).isEqualTo(DispositionApproval.REQUESTED);
        assertThat(disp.getValue().getRequestedBy()).isEqualTo("rm-anil");
        assertThat(disp.getValue().getDecidedBy()).isNull();
        verify(auditRepo, never()).save(any());
    }

    @Test
    void pendingRequestLeavesBreachStandingAndIsShownOnTheRow() {
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.BREACHED, "100")));
        when(masterRepo.findAllById(any())).thenReturn(List.of(master("50", CovenantCategory.FINANCIAL)));
        CovenantDisposition pending =
                disposition("1", "100", CovenantDispositionStatus.WAIVER, CovenantStatusSource.USER, null);
        pending.setApprovalStatus(DispositionApproval.REQUESTED);
        pending.setRequestedBy("rm-anil");
        when(dispositionRepo.findByCpId("CP-1")).thenReturn(List.of(pending));

        CpReviewRow row = service().reviewRows("CP-1").get(0);

        assertThat(row.dispositionStatus()).isEqualTo(CovenantDispositionStatus.BREACHED);
        assertThat(row.approvalStatus()).isEqualTo(DispositionApproval.REQUESTED);
        assertThat(row.requestedStatus()).isEqualTo(CovenantDispositionStatus.WAIVER);
        assertThat(row.requestedBy()).isEqualTo("rm-anil");
    }

    @Test
    void pastExtensionDateIsRejected() {
        assertThatThrownBy(() -> service().setDisposition("CP-1", "1", new SetDispositionRequest(
                CovenantDispositionStatus.EXTENSION, LocalDate.now(Clock.systemUTC()).minusDays(1), null, null)))
                .satisfies(t -> assertStatus(t, 400));
        verify(dispositionRepo, never()).save(any());
    }

    @Test
    void approverApprovesAnotherUsersRequestAndItIsAudited() {
        CovenantDisposition pending = requested("100");
        when(dispositionRepo.findByCpIdAndAppliedCovenantId("CP-1", "1")).thenReturn(Optional.of(pending));
        stubBreachedLatest("1", "100", "CP-1");
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.BREACHED, "100")));
        as("approver-1", "APPROVER");

        service().decideDisposition("CP-1", "1",
                new DispositionDecisionRequest(DispositionDecisionRequest.Decision.APPROVE, "ok"));

        assertThat(pending.getApprovalStatus()).isEqualTo(DispositionApproval.APPROVED);
        assertThat(pending.getDecidedBy()).isEqualTo("approver-1");
        assertThat(pending.getDecidedAt()).isNotNull();
        assertThat(pending.getDecisionNote()).isEqualTo("ok");
        ArgumentCaptor<CovenantStatusAudit> audit = ArgumentCaptor.forClass(CovenantStatusAudit.class);
        verify(auditRepo).save(audit.capture());
        assertThat(audit.getValue().getOldStatus()).isEqualTo(CovenantDispositionStatus.BREACHED);
        assertThat(audit.getValue().getNewStatus()).isEqualTo(CovenantDispositionStatus.WAIVER);
        assertThat(audit.getValue().getChangedBy()).isEqualTo("approver-1");
        assertThat(audit.getValue().getRationale()).isEqualTo("ok");
    }

    @Test
    void rejectionLeavesBreachAndIsNotAudited() {
        CovenantDisposition pending = requested("100");
        when(dispositionRepo.findByCpIdAndAppliedCovenantId("CP-1", "1")).thenReturn(Optional.of(pending));
        stubBreachedLatest("1", "100", "CP-1");
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.BREACHED, "100")));
        as("admin-1", "ADMIN");

        service().decideDisposition("CP-1", "1",
                new DispositionDecisionRequest(DispositionDecisionRequest.Decision.REJECT, null));

        assertThat(pending.getApprovalStatus()).isEqualTo(DispositionApproval.REJECTED);
        verify(auditRepo, never()).save(any());
    }

    @Test
    void approvalWithoutNoteFallsBackToRequestNote() {
        CovenantDisposition pending = requested("100");
        pending.setNote("rm reason");
        when(dispositionRepo.findByCpIdAndAppliedCovenantId("CP-1", "1")).thenReturn(Optional.of(pending));
        stubBreachedLatest("1", "100", "CP-1");
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.BREACHED, "100")));
        as("approver-1", "APPROVER");

        service().decideDisposition("CP-1", "1",
                new DispositionDecisionRequest(DispositionDecisionRequest.Decision.APPROVE, null));

        ArgumentCaptor<CovenantStatusAudit> audit = ArgumentCaptor.forClass(CovenantStatusAudit.class);
        verify(auditRepo).save(audit.capture());
        assertThat(audit.getValue().getRationale()).isEqualTo("rm reason");
    }

    @Test
    void decisionRules() {
        DispositionDecisionRequest approve =
                new DispositionDecisionRequest(DispositionDecisionRequest.Decision.APPROVE, null);
        // not an approver, or not signed in
        as("rm-anil", "RM");
        assertThatThrownBy(() -> service().decideDisposition("CP-1", "1", approve))
                .satisfies(t -> assertStatus(t, 403));
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service().decideDisposition("CP-1", "1", approve))
                .satisfies(t -> assertStatus(t, 403));

        as("rm-anil", "APPROVER");
        // nothing pending
        CovenantDisposition approved =
                disposition("1", "100", CovenantDispositionStatus.WAIVER, CovenantStatusSource.USER, null);
        when(dispositionRepo.findByCpIdAndAppliedCovenantId("CP-1", "1")).thenReturn(Optional.of(approved));
        assertThatThrownBy(() -> service().decideDisposition("CP-1", "1", approve))
                .satisfies(t -> assertStatus(t, 404));
        // self-approval
        when(dispositionRepo.findByCpIdAndAppliedCovenantId("CP-1", "1")).thenReturn(Optional.of(requested("100")));
        stubBreachedLatest("1", "100", "CP-1");
        assertThatThrownBy(() -> service().decideDisposition("CP-1", "1", approve))
                .satisfies(t -> assertStatus(t, 403));
        // re-assessed since the request
        as("approver-1", "APPROVER");
        when(dispositionRepo.findByCpIdAndAppliedCovenantId("CP-1", "1")).thenReturn(Optional.of(requested("99")));
        assertThatThrownBy(() -> service().decideDisposition("CP-1", "1", approve))
                .satisfies(t -> assertStatus(t, 409));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(Page.empty());
        assertThatThrownBy(() -> service().decideDisposition("CP-1", "1", approve))
                .satisfies(t -> assertStatus(t, 409));
        verify(auditRepo, never()).save(any());
    }

    @Test
    void requesterWithdrawsAPendingRequest() {
        CovenantDisposition pending = requested("100");
        when(dispositionRepo.findByCpIdAndAppliedCovenantId("CP-1", "1")).thenReturn(Optional.of(pending));
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.BREACHED, "100")));
        as("rm-anil", "RM");

        service().withdrawDisposition("CP-1", "1", null);

        assertThat(pending.getApprovalStatus()).isEqualTo(DispositionApproval.WITHDRAWN);
        assertThat(pending.getDecidedBy()).isEqualTo("rm-anil");
        assertThat(pending.getDecisionNote()).isEqualTo("Withdrawn by the requester");
        verify(auditRepo, never()).save(any());
    }

    @Test
    void withdrawRules() {
        when(dispositionRepo.findByCpIdAndAppliedCovenantId("CP-1", "1")).thenReturn(Optional.of(requested("100")));
        as("rm-priya", "RM");
        assertThatThrownBy(() -> service().withdrawDisposition("CP-1", "1", null))
                .satisfies(t -> assertStatus(t, 403));

        CovenantDisposition pending = requested("100");
        when(dispositionRepo.findByCpIdAndAppliedCovenantId("CP-1", "1")).thenReturn(Optional.of(pending));
        when(monitoringService.covenantsForCp("CP-1"))
                .thenReturn(List.of(covenant("1", "50", "DSCR > 1.2", AssessmentResult.BREACHED, "100")));
        as("admin-1", "ADMIN");
        service().withdrawDisposition("CP-1", "1", "  raised by mistake ");
        assertThat(pending.getDecisionNote()).isEqualTo("raised by mistake");

        when(dispositionRepo.findByCpIdAndAppliedCovenantId("CP-1", "1")).thenReturn(Optional.of(pending));
        assertThatThrownBy(() -> service().withdrawDisposition("CP-1", "1", null))
                .satisfies(t -> assertStatus(t, 404));
    }

    @Test
    void compliantCovenantCloseToItsLimitIsAtRisk() {
        lenient().when(settingsService.atRiskHeadroomPercent()).thenReturn(10);
        when(monitoringService.covenantsForCp("CP-1")).thenReturn(List.of(
                covenant("1", "50", "DSCR > 1.2", AssessmentResult.COMPLIANT, "100"),
                covenant("2", "50", "DSCR > 1.2", AssessmentResult.COMPLIANT, "101")));
        when(masterRepo.findAllById(any())).thenReturn(List.of(master("50", CovenantCategory.FINANCIAL)));
        when(assessmentRepo.findAllById(any())).thenReturn(List.of(assessment("100", "1.25"), assessment("101", "1.5")));

        List<CpReviewRow> rows = service().reviewRows("CP-1");

        assertThat(rows.get(0).atRisk()).isTrue();
        assertThat(rows.get(1).atRisk()).isFalse();
    }

    private static CovenantDisposition requested(String assessmentId) {
        CovenantDisposition d =
                disposition("1", assessmentId, CovenantDispositionStatus.WAIVER, CovenantStatusSource.USER, null);
        d.setApprovalStatus(DispositionApproval.REQUESTED);
        d.setRequestedBy("rm-anil");
        return d;
    }

    private static void as(String user, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    // -- helpers ---------------------------------------------------------

    private void stubBreachedLatest(String appliedId, String assessmentId, String cpId) {
        stubLatest(appliedId, assessmentId, cpId, AssessmentResult.BREACHED);
    }

    private void stubLatest(String appliedId, String assessmentId, String cpId, AssessmentResult result) {
        AppliedCovenant applied = new AppliedCovenant();
        applied.setCpId(cpId);
        Assessment a = new Assessment();
        setId(a, assessmentId);
        a.setAppliedCovenant(applied);
        a.setResult(result);
        Page<Assessment> page = new PageImpl<>(List.of(a));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq(appliedId), any(PageRequest.class)))
                .thenReturn(page);
    }

    private static CovenantOnCp covenant(String appliedId, String masterId, String expr,
                                         AssessmentResult latestResult, String latestAssessmentId) {
        return new CovenantOnCp(appliedId, masterId, "Cov-" + appliedId, CovenantCategory.FINANCIAL,
                null, LocalDate.now(), null, null, null, latestResult, Instant.now(), latestAssessmentId,
                LocalDate.now().plusDays(30), null, 0L, expr, null, null, null);
    }

    /** An AUTO master — the type the grid shows Actual + Headroom for, whatever the category. */
    private static CovenantMaster master(String id, CovenantCategory category) {
        return master(id, category, AssessmentType.AUTO);
    }

    private static CovenantMaster master(String id, CovenantCategory category, AssessmentType type) {
        CovenantMaster m = new CovenantMaster();
        setId(m, id);
        m.setName("M-" + id);
        m.setCategory(category);
        m.setAssessmentType(type);
        m.setConditionExpression("DSCR > 1");
        return m;
    }

    private static Assessment assessment(String id, String computedValue) {
        Assessment a = new Assessment();
        setId(a, id);
        a.setComputedValue(computedValue);
        a.setComputedValueType("Double");
        return a;
    }

    private static CovenantDisposition disposition(String appliedId, String assessmentId,
                                                   CovenantDispositionStatus status,
                                                   CovenantStatusSource source, LocalDate extensionDate) {
        CovenantDisposition d = new CovenantDisposition();
        d.setCpId("CP-1");
        d.setAppliedCovenantId(appliedId);
        d.setAssessmentId(assessmentId);
        d.setStatus(status);
        d.setStatusSource(source);
        d.setExtensionDate(extensionDate);
        return d;
    }

    private static CovenantStatusAudit audit(String cpId, String appliedId) {
        return new CovenantStatusAudit(cpId, appliedId, "100",
                CovenantDispositionStatus.BREACHED, CovenantDispositionStatus.WAIVER,
                null, null, CovenantStatusSource.USER, "note", "rm", Instant.now());
    }

    private static void assertStatus(Throwable t, int status) {
        assertThat(t).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(status));
    }

    private static void setId(Object target, String id) {
        try {
            Field f = target.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(target, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
