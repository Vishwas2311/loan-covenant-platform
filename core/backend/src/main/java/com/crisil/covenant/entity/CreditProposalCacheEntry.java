package com.crisil.covenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "cov_credit_proposal_cache")
public class CreditProposalCacheEntry {

    @Id
    @Column(name = "cp_id", length = 64, nullable = false)
    private String cpId;

    @Column(name = "business_segment", length = 64)
    private String businessSegment;

    @Column(name = "sector", length = 64)
    private String sector;

    /** Denormalized forward copy of the CP's obligor (source of truth is cov_obligor_cp_map). */
    @Column(name = "obligor_id", length = 64)
    private String obligorId;

    /**
     * The CP's immediate predecessor in its renewal lineage (source is the external CP service's
     * {@code parentCpId}). Denormalized here so the cycle viewer walks the chain by a plain lookup
     * rather than a per-hop remote call (US-MON-23). Nullable — a root CP has no parent.
     */
    @Column(name = "parent_cp_id", length = 64)
    private String parentCpId;

    /** userId of the CP's Relationship Manager (portfolio ownership; source is the external CP service). */
    @Column(name = "rm_user_id", length = 64)
    private String rmUserId;

    /** userId of the CP's Wholesale Relationship Manager (portfolio ownership; source is the CP service). */
    @Column(name = "wrm_user_id", length = 64)
    private String wrmUserId;

    @Column(name = "attributes_json", length = 4000, nullable = false)
    private String attributesJson;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    public String getCpId() { return cpId; }
    public void setCpId(String cpId) { this.cpId = cpId; }

    public String getBusinessSegment() { return businessSegment; }
    public void setBusinessSegment(String s) { this.businessSegment = s; }

    public String getSector() { return sector; }
    public void setSector(String s) { this.sector = s; }

    public String getObligorId() { return obligorId; }
    public void setObligorId(String obligorId) { this.obligorId = obligorId; }

    public String getParentCpId() { return parentCpId; }
    public void setParentCpId(String parentCpId) { this.parentCpId = parentCpId; }

    public String getRmUserId() { return rmUserId; }
    public void setRmUserId(String rmUserId) { this.rmUserId = rmUserId; }

    public String getWrmUserId() { return wrmUserId; }
    public void setWrmUserId(String wrmUserId) { this.wrmUserId = wrmUserId; }

    public String getAttributesJson() { return attributesJson; }
    public void setAttributesJson(String s) { this.attributesJson = s; }

    public Instant getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Instant t) { this.fetchedAt = t; }
}
