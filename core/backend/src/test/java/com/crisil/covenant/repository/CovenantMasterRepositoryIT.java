package com.crisil.covenant.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.enums.CovenantCategory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.crisil.covenant.config.AuditingConfig;
import com.crisil.covenant.entity.CovenantMaster;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AuditingConfig.class)
@ActiveProfiles("test")
class CovenantMasterRepositoryIT {

    @Autowired
    private CovenantMasterRepository repository;

    @Test
    void persistsAndRetrievesEntity() {
        CovenantMaster e = new CovenantMaster();
        e.setName("DSCR > 1.7");
        e.setCategory(CovenantCategory.FINANCIAL);
        e.setConditionExpression("DSCR > 1");
        e.setBusinessSegments(List.of("RETAIL"));
        e.setActive(true);

        CovenantMaster saved = repository.save(e);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedDate()).isNotNull();
        assertThat(saved.getCreatedBy()).isEqualTo("system");
        assertThat(saved.getVersion()).isNotNull();

        assertThat(repository.findByNameIgnoreCase("dscr > 1.7")).isPresent();
        assertThat(repository.existsByNameIgnoreCase("DSCR > 1.7")).isTrue();
        assertThat(repository.existsByNameIgnoreCase("missing")).isFalse();
    }
}
