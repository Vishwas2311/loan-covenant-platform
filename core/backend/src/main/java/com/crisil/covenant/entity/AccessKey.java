package com.crisil.covenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** Key of {@link PortalAccess}: one borrower user on one CP. */
@Embeddable
public class AccessKey implements Serializable {
    private static final long serialVersionUID = 1L;

    @Column(name = "cp_id", length = 64, nullable = false)
    private String cpId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    public AccessKey() {}

    public AccessKey(String cpId, String userId) {
        this.cpId = cpId;
        this.userId = userId;
    }

    public String getCpId() { return cpId; }
    public String getUserId() { return userId; }

    @Override
    public boolean equals(Object o) {
        return o instanceof AccessKey k && Objects.equals(cpId, k.cpId) && Objects.equals(userId, k.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cpId, userId);
    }
}
