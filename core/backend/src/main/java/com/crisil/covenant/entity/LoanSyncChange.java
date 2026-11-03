package com.crisil.covenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** C8: one thing a sync run found changed on a CP. */
@Entity
@Table(name = "cov_loan_sync_change")
public class LoanSyncChange {
    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id = UUID.randomUUID().toString();

    @Column(name = "run_id", length = 36, nullable = false)
    private String runId;

    @Column(name = "cp_id", length = 64, nullable = false)
    private String cpId;

    @Column(name = "field", length = 32, nullable = false)
    private String field;

    @Column(name = "old_value", length = 255)
    private String oldValue;

    @Column(name = "new_value", length = 255)
    private String newValue;

    public LoanSyncChange() {}

    public LoanSyncChange(String runId, String cpId, String field, String oldValue, String newValue) {
        this.runId = runId;
        this.cpId = cpId;
        this.field = field;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public String getRunId() { return runId; }
    public String getCpId() { return cpId; }
    public String getField() { return field; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
}
