package com.crisil.covenant.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.covenant.config.AuditingConfig;
import com.crisil.covenant.entity.ObligorCpMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/**
 * Persistence tests for the obligor -> CP mapping against H2 in MSSQL mode (V5 migration). Verifies
 * both lookup directions — forward (a CP's obligor, for execution) and reverse (an obligor's CPs, for
 * fan-out) — and the {@code UNIQUE(cp_id)} guard enforcing "many CPs -> one obligor".
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AuditingConfig.class)
@ActiveProfiles("test")
class ObligorCpMapRepositoryIT {

    @Autowired
    private ObligorCpMapRepository repository;

    private static ObligorCpMap mapping(String cpId, String obligorId) {
        ObligorCpMap m = new ObligorCpMap();
        m.setCpId(cpId);
        m.setObligorId(obligorId);
        return m;
    }

    @Test
    void persistsAndResolvesForwardByCp() {
        ObligorCpMap saved = repository.saveAndFlush(mapping("CP-1", "OBL-1"));
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedBy()).isEqualTo("system"); // auditing populated
        assertThat(saved.getVersion()).isNotNull();

        assertThat(repository.findByCpId("CP-1")).isPresent();
        assertThat(repository.findByCpId("CP-1").orElseThrow().getObligorId()).isEqualTo("OBL-1");
        assertThat(repository.findByCpId("CP-UNKNOWN")).isEmpty();
    }

    @Test
    void fansOutReverseByObligor() {
        repository.saveAndFlush(mapping("CP-1", "OBL-1"));
        repository.saveAndFlush(mapping("CP-2", "OBL-1"));
        repository.saveAndFlush(mapping("CP-3", "OBL-2"));

        assertThat(repository.findCpIdsByObligorId("OBL-1"))
                .containsExactlyInAnyOrder("CP-1", "CP-2");
        assertThat(repository.findCpIdsByObligorId("OBL-2")).containsExactly("CP-3");
        assertThat(repository.findCpIdsByObligorId("OBL-NONE")).isEmpty();
    }

    @Test
    void uniqueOnCpIdRejectsASecondObligorForTheSameCp() {
        repository.saveAndFlush(mapping("CP-1", "OBL-1"));

        // A distinct row for the same CP violates UNIQUE(cp_id): a CP belongs to exactly one obligor.
        assertThatThrownBy(() -> repository.saveAndFlush(mapping("CP-1", "OBL-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
