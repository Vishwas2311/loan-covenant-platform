package com.crisil.covenant.entity;

import com.crisil.covenant.util.ChangeTypeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "cov_applied_covenant_audit")
public class AppliedCovenantAudit {

    public enum ChangeType { FREQUENCY, CRON, STATUS }

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "applied_covenant_id", nullable = false)
    private String appliedCovenantId;

    @Column(name = "change_type", nullable = false, length = 32)
    @Convert(converter = ChangeTypeConverter.class)
    private ChangeType changeType;

    @Column(name = "old_value", length = 128)
    private String oldValue;

    @Column(name = "new_value", length = 128)
    private String newValue;

    @Column(name = "changed_date", nullable = false)
    private Instant changedDate;

    @Column(name = "changed_by", nullable = false, length = 50)
    private String changedBy;

    public AppliedCovenantAudit() {}

    public AppliedCovenantAudit(String appliedCovenantId, ChangeType changeType,
                                String oldValue, String newValue,
                                Instant changedDate, String changedBy) {
        this.appliedCovenantId = appliedCovenantId;
        this.changeType = changeType;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.changedDate = changedDate;
        this.changedBy = changedBy;
    }

    public String getId() { return id; }
    public String getAppliedCovenantId() { return appliedCovenantId; }
    public ChangeType getChangeType() { return changeType; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public Instant getChangedDate() { return changedDate; }
    public String getChangedBy() { return changedBy; }
}
