package com.crisil.covenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

/**
 * Maps a credit proposal (CP) to its obligor. The relationship is one obligor to many CPs, so the
 * unique key is on {@code cp_id} (each CP belongs to exactly one obligor) while {@code obligor_id}
 * is indexed for the reverse fan-out (all CPs of one obligor). Rows are captured when a CP is looked
 * up (see {@code RecommendationController}); the mapping is the source of truth used to bind an
 * obligor-keyed source read down to each of the obligor's CPs.
 */
@Entity
@Table(name = "cov_obligor_cp_map")
public class ObligorCpMap extends AuditableEntity {

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "obligor_id", nullable = false, length = 64)
    private String obligorId;

    @Column(name = "cp_id", nullable = false, length = 64)
    private String cpId;

    public String getId() {
        return id;
    }

    public String getObligorId() {
        return obligorId;
    }

    public void setObligorId(String obligorId) {
        this.obligorId = obligorId;
    }

    public String getCpId() {
        return cpId;
    }

    public void setCpId(String cpId) {
        this.cpId = cpId;
    }
}
