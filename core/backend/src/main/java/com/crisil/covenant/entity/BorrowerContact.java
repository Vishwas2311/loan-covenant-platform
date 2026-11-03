package com.crisil.covenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** C2: the person at the borrower who receives compliance-certificate reminders for one CP. */
@Entity
@Table(name = "cov_borrower_contact")
public class BorrowerContact {

    @Id
    @Column(name = "cp_id", length = 64, nullable = false, updatable = false)
    private String cpId;

    @Column(name = "contact_name", length = 200, nullable = false)
    private String contactName;

    @Column(name = "email", length = 254, nullable = false)
    private String email;

    @Column(name = "reminders_on", nullable = false)
    private boolean remindersOn = true;

    /** How many days before a certificate is due the first reminder goes out. */
    @Column(name = "days_before", nullable = false)
    private int daysBefore = 7;

    @Column(name = "updated_by", length = 64, nullable = false)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public BorrowerContact() {}

    public BorrowerContact(String cpId) {
        this.cpId = cpId;
    }

    public String getCpId() { return cpId; }
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public boolean isRemindersOn() { return remindersOn; }
    public void setRemindersOn(boolean remindersOn) { this.remindersOn = remindersOn; }
    public int getDaysBefore() { return daysBefore; }
    public void setDaysBefore(int daysBefore) { this.daysBefore = daysBefore; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void touched(String by, Instant at) {
        this.updatedBy = by;
        this.updatedAt = at;
    }
}
