package com.crisil.covenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/** C1: a borrower user the bank allowed to use the portal for a CP. */
@Entity
@Table(name = "cov_portal_access")
public class PortalAccess {

    @EmbeddedId
    private AccessKey key;

    @Column(name = "granted_by", length = 64, nullable = false)
    private String grantedBy;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    public PortalAccess() {}

    public PortalAccess(String cpId, String userId, String grantedBy, Instant grantedAt) {
        this.key = new AccessKey(cpId, userId);
        this.grantedBy = grantedBy;
        this.grantedAt = grantedAt;
    }

    public String getCpId() { return key.getCpId(); }
    public String getUserId() { return key.getUserId(); }
    public String getGrantedBy() { return grantedBy; }
    public Instant getGrantedAt() { return grantedAt; }
}
