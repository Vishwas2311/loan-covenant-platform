package com.crisil.covenant.entity;

import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.UuidGenerator;
import com.crisil.covenant.enums.ScheduleStatus;

@Entity
@Table(name = "cov_monitoring_schedule")
public class MonitoringSchedule extends AuditableEntity {

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "applied_covenant_id", nullable = false)
    private AppliedCovenant appliedCovenant;

    @Column(name = "quartz_job_key", nullable = false, length = 128)
    private String quartzJobKey;

    @Column(name = "cron_expression", nullable = false, length = 64)
    private String cronExpression;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "status", nullable = false, length = 16)
    private ScheduleStatus status = ScheduleStatus.SCHEDULED;

    public String getId() { return id; }

    public AppliedCovenant getAppliedCovenant() { return appliedCovenant; }
    public void setAppliedCovenant(AppliedCovenant a) { this.appliedCovenant = a; }

    public String getQuartzJobKey() { return quartzJobKey; }
    public void setQuartzJobKey(String k) { this.quartzJobKey = k; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String c) { this.cronExpression = c; }

    public Instant getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(Instant t) { this.nextRunAt = t; }

    public Instant getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(Instant t) { this.lastRunAt = t; }

    public ScheduleStatus getStatus() { return status; }
    public void setStatus(ScheduleStatus s) { this.status = s; }
}
