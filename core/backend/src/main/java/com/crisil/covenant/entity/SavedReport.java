package com.crisil.covenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** C11: a report a user saved (which columns, which filters) and how often it is e-mailed. */
@Entity
@Table(name = "cov_saved_report")
public class SavedReport {

    public enum Schedule { NONE, DAILY, WEEKLY, MONTHLY }

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id = UUID.randomUUID().toString();

    @Column(name = "owner", length = 64, nullable = false, updatable = false)
    private String owner;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Column(name = "definition_json", length = 4000, nullable = false)
    private String definitionJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule", length = 16, nullable = false)
    private Schedule schedule = Schedule.NONE;

    @Column(name = "email_to", length = 500)
    private String emailTo;

    @Column(name = "last_sent_on")
    private LocalDate lastSentOn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public SavedReport() {}

    public SavedReport(String owner, Instant createdAt) {
        this.owner = owner;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getOwner() { return owner; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDefinitionJson() { return definitionJson; }
    public void setDefinitionJson(String definitionJson) { this.definitionJson = definitionJson; }
    public Schedule getSchedule() { return schedule; }
    public void setSchedule(Schedule schedule) { this.schedule = schedule; }
    public String getEmailTo() { return emailTo; }
    public void setEmailTo(String emailTo) { this.emailTo = emailTo; }
    public LocalDate getLastSentOn() { return lastSentOn; }
    public void setLastSentOn(LocalDate lastSentOn) { this.lastSentOn = lastSentOn; }
    public Instant getCreatedAt() { return createdAt; }
}
