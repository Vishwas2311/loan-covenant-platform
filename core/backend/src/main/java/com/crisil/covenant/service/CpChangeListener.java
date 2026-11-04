package com.crisil.covenant.service;

import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.entity.CertificateRequirement;
import com.crisil.covenant.entity.ComplianceCertificate;
import com.crisil.covenant.entity.CovenantDisposition;
import com.crisil.covenant.entity.MonitoringSchedule;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JPA callbacks on the entities the work queue is built from: each write tells {@link TaskSyncTracker}
 * which CP changed. Only identifiers are read (never a lazy association's state), and a failure here
 * never fails the write; the tracker's periodic re-sync covers anything missed.
 */
public class CpChangeListener {

    private static final Logger LOG = LoggerFactory.getLogger(CpChangeListener.class);

    @PostPersist
    @PostUpdate
    @PostRemove
    void changed(Object entity) {
        try {
            if (entity instanceof AppliedCovenant a) {
                TaskSyncTracker.changed(a.getCpId(), a.getId());
            } else if (entity instanceof Assessment a && a.getAppliedCovenant() != null) {
                TaskSyncTracker.changed(null, a.getAppliedCovenant().getId());
            } else if (entity instanceof MonitoringSchedule s && s.getAppliedCovenant() != null) {
                TaskSyncTracker.changed(null, s.getAppliedCovenant().getId());
            } else if (entity instanceof CovenantDisposition d) {
                TaskSyncTracker.changed(d.getCpId(), null);
            } else if (entity instanceof CertificateRequirement r) {
                TaskSyncTracker.changed(r.getCpId(), null);
            } else if (entity instanceof ComplianceCertificate c) {
                TaskSyncTracker.changed(c.getCpId(), null);
            }
        } catch (RuntimeException ex) {
            LOG.debug("Work-queue change mark skipped");
        }
    }
}
