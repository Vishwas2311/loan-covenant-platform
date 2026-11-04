package com.crisil.covenant.service;

import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.domain.CpMonitoringSummary;
import com.crisil.covenant.dto.AlertView;
import com.crisil.covenant.dto.CpReviewRow;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.DispositionApproval;
import com.crisil.covenant.enums.TimingStatus;
import com.crisil.covenant.security.Roles;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * B6: what needs the signed-in user's attention across the CPs they look after, built from the same
 * review rows the CP workspace shows. Nothing is stored; the list is worked out on each request.
 */
@Service
@Transactional(readOnly = true)
public class AlertService {

    private static final Map<String, Integer> ORDER = Map.of(
            "APPROVAL", 0, "BREACH", 1, "OVERDUE", 2, "REVIEW", 3, "AT_RISK", 4);

    private final MonitoringService monitoringService;
    private final ReviewService reviewService;

    public AlertService(MonitoringService monitoringService, ReviewService reviewService) {
        this.monitoringService = monitoringService;
        this.reviewService = reviewService;
    }

    public List<AlertView> alertsFor(String user) {
        boolean approver = CurrentUserContext.hasAnyRole(Roles.APPROVER, Roles.ADMIN);
        List<AlertView> alerts = new ArrayList<>();
        for (CpMonitoringSummary cp : monitoringService.ownedSummary(user)) {
            for (CpReviewRow r : reviewService.reviewRows(cp.cpId())) {
                alerts.addAll(alertsForRow(cp.cpId(), r, user, approver));
            }
        }
        alerts.sort(Comparator.comparing((AlertView a) -> ORDER.getOrDefault(a.kind(), 9))
                .thenComparing(AlertView::cpId));
        return alerts;
    }

    static List<AlertView> alertsForRow(String cpId, CpReviewRow r, String user, boolean approver) {
        List<AlertView> out = new ArrayList<>();
        boolean requested = r.approvalStatus() == DispositionApproval.REQUESTED;
        if (requested && approver && !Objects.equals(r.requestedBy(), user)) {
            out.add(new AlertView("APPROVAL", cpId, r.appliedCovenantId(), r.covenantName(),
                    r.requestedBy() + " asks for a " + r.requestedStatus().name().toLowerCase(Locale.ROOT) + " on " + r.covenantName()));
        } else if (r.dispositionable() && !requested) {
            out.add(new AlertView("BREACH", cpId, r.appliedCovenantId(), r.covenantName(),
                    r.covenantName() + " is breached and nothing has been decided"));
        }
        if (r.timingStatus() == TimingStatus.OVERDUE) {
            out.add(new AlertView("OVERDUE", cpId, r.appliedCovenantId(), r.covenantName(),
                    r.covenantName() + " check is " + r.overdueByDays() + " day(s) overdue"));
        }
        if (r.latestResult() == AssessmentResult.PENDING_REVIEW) {
            out.add(new AlertView("REVIEW", cpId, r.appliedCovenantId(), r.covenantName(),
                    r.covenantName() + " needs a person to record the result"));
        }
        if (r.atRisk()) {
            out.add(new AlertView("AT_RISK", cpId, r.appliedCovenantId(), r.covenantName(),
                    r.covenantName() + " is close to its limit"));
        }
        return out;
    }
}
