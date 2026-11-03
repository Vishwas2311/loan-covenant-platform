package com.crisil.covenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** C8: one run of the nightly check against the bank's loan system. */
@Entity
@Table(name = "cov_loan_sync_run")
public class LoanSyncRun {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id = UUID.randomUUID().toString();

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "cps_checked", nullable = false)
    private int cpsChecked;

    @Column(name = "cps_changed", nullable = false)
    private int cpsChanged;

    @Column(name = "cps_closed", nullable = false)
    private int cpsClosed;

    @Column(name = "errors", nullable = false)
    private int errors;

    @Column(name = "triggered_by", length = 64, nullable = false)
    private String triggeredBy;

    public LoanSyncRun() {}

    public LoanSyncRun(Instant startedAt, String triggeredBy) {
        this.startedAt = startedAt;
        this.triggeredBy = triggeredBy;
    }

    public void finish(Instant at, int checked, int changed, int closed, int errorCount) {
        this.finishedAt = at;
        this.cpsChecked = checked;
        this.cpsChanged = changed;
        this.cpsClosed = closed;
        this.errors = errorCount;
    }

    public String getId() { return id; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public int getCpsChecked() { return cpsChecked; }
    public int getCpsChanged() { return cpsChanged; }
    public int getCpsClosed() { return cpsClosed; }
    public int getErrors() { return errors; }
    public String getTriggeredBy() { return triggeredBy; }
}
