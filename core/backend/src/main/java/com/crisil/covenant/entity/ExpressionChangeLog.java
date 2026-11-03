package com.crisil.covenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "cov_expression_change_log")
public class ExpressionChangeLog {

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "covenant_id", nullable = false)
    private String covenantId;

    @Column(name = "old_expression", length = 2000)
    private String oldExpression;

    @Column(name = "new_expression", nullable = false, length = 2000)
    private String newExpression;

    @Column(name = "changed_date", nullable = false)
    private Instant changedDate;

    @Column(name = "changed_by", nullable = false, length = 50)
    private String changedBy;

    public ExpressionChangeLog() {}

    public ExpressionChangeLog(String covenantId, String oldExpression, String newExpression,
                               Instant changedDate, String changedBy) {
        this.covenantId = covenantId;
        this.oldExpression = oldExpression;
        this.newExpression = newExpression;
        this.changedDate = changedDate;
        this.changedBy = changedBy;
    }

    public String getId() { return id; }
    public String getCovenantId() { return covenantId; }
    public String getOldExpression() { return oldExpression; }
    public String getNewExpression() { return newExpression; }
    public Instant getChangedDate() { return changedDate; }
    public String getChangedBy() { return changedBy; }
}
