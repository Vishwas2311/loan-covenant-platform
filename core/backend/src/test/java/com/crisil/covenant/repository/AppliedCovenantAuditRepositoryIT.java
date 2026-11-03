package com.crisil.covenant.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.config.AuditingConfig;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.AppliedCovenantAudit;
import com.crisil.covenant.entity.AppliedCovenantAudit.ChangeType;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.enums.Frequency;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Persistence test proving the audit row's {@code change_type} is written as the lower-case token the
 * {@code ck_applied_audit_type CHECK (change_type IN ('frequency','cron','status'))} in
 * {@code V1__baseline.sql} permits. Before {@link com.crisil.covenant.util.ChangeTypeConverter} the field
 * was a raw {@code @Enumerated(STRING)} persisting UPPERCASE names, so every real insert via
 * {@code AppliedCovenantService.updateSchedule} tripped the CHECK (SQLState 23513) → HTTP 500 — a gap the
 * mock-based {@code AppliedCovenantServiceTest} could never catch. This runs against a live H2/MSSQL-mode
 * schema, which evaluates the CHECK on flush, so an uppercase regression would throw right here.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AuditingConfig.class)
@ActiveProfiles("test")
class AppliedCovenantAuditRepositoryIT {

    @Autowired private AppliedCovenantAuditRepository auditRepository;
    @Autowired private AppliedCovenantRepository appliedRepository;
    @Autowired private CovenantMasterRepository masterRepository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void everyChangeTypeSatisfiesTheLowerCaseCheckAndRoundTrips() {
        // fk_applied_audit -> cov_applied_covenant(id): seed a parent covenant graph first.
        String acId = seedAppliedCovenant();

        for (ChangeType type : ChangeType.values()) {
            // saveAndFlush forces the INSERT now, so H2 evaluates the CHECK immediately.
            AppliedCovenantAudit saved = auditRepository.saveAndFlush(
                    new AppliedCovenantAudit(acId, type, "old", "new", Instant.now(), "tester"));

            // Decode path: reading back through the entity yields the original constant.
            AppliedCovenantAudit reloaded = auditRepository.findById(saved.getId()).orElseThrow();
            assertThat(reloaded.getChangeType()).isEqualTo(type);

            // Encode path: the raw column holds the lower-case token the CHECK permits.
            String raw = jdbc.queryForObject(
                    "select change_type from cov_applied_covenant_audit where id = ?",
                    String.class, saved.getId());
            assertThat(raw).isEqualTo(type.name().toLowerCase(Locale.ROOT));
        }

        assertThat(auditRepository.findByAppliedCovenantIdOrderByChangedDateDesc(acId))
                .hasSize(ChangeType.values().length);
    }

    private String seedAppliedCovenant() {
        CovenantMaster master = masterRepository.saveAndFlush(newMaster());
        AppliedCovenant applied = appliedRepository.saveAndFlush(newApplied("CP-AUDIT-1", master));
        return applied.getId();
    }

    private static CovenantMaster newMaster() {
        CovenantMaster cm = new CovenantMaster();
        cm.setName("Audit IT DSCR");
        cm.setCategory(CovenantCategory.FINANCIAL);
        cm.setConditionExpression("DSCR > 1");
        cm.setBusinessSegments(List.of());
        cm.setActive(true);
        return cm;
    }

    private static AppliedCovenant newApplied(String cpId, CovenantMaster master) {
        AppliedCovenant a = new AppliedCovenant();
        a.setCpId(cpId);
        a.setCovenantMaster(master);
        a.setFrequency(Frequency.DAILY);
        a.setCronExpression(Frequency.DAILY.defaultCron());
        a.setStartDate(LocalDate.now());
        a.setSnapshotJson("{}");
        return a;
    }
}
