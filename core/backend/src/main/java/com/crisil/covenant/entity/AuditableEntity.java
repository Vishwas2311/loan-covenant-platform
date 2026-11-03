package com.crisil.covenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import java.time.Instant;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Common audit columns shared by the mutable {@code cov_*} entities.
 *
 * <p>Conventions (aligned with the consolidated {@code all_tables.sql} schema):
 * date columns are {@code created_date}/{@code modified_date}; user columns are {@code VARCHAR(50)};
 * {@code version} is an application-managed {@code VARCHAR(36)} string (JPA optimistic locking is not
 * used because {@code @Version} does not support {@code String} fields — the column defaults to
 * {@code "0"}).
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity {

    @CreatedDate
    @Column(name = "created_date", nullable = false, updatable = false)
    private Instant createdDate;

    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false, length = 50)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "modified_date", nullable = false)
    private Instant modifiedDate;

    @LastModifiedBy
    @Column(name = "modified_by", nullable = false, length = 50)
    private String modifiedBy;

    @Column(name = "version", nullable = false, length = 36)
    private String version;

    @PrePersist
    void ensureVersion() {
        if (version == null) {
            version = "0";
        }
    }

    public Instant getCreatedDate() {
        return createdDate;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getModifiedDate() {
        return modifiedDate;
    }

    public String getModifiedBy() {
        return modifiedBy;
    }

    public String getVersion() {
        return version;
    }
}
