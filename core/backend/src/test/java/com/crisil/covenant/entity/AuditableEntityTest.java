package com.crisil.covenant.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.entity.CovenantMaster;
import java.lang.reflect.Field;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuditableEntityTest {

    @Test
    void gettersReflectInjectedAuditValues() throws Exception {
        CovenantMaster entity = new CovenantMaster();
        Instant now = Instant.parse("2026-06-04T10:00:00Z");
        setField(entity, "createdDate", now);
        setField(entity, "createdBy", "alice");
        setField(entity, "modifiedDate", now.plusSeconds(60));
        setField(entity, "modifiedBy", "bob");
        setField(entity, "version", "7");

        assertThat(entity.getCreatedDate()).isEqualTo(now);
        assertThat(entity.getCreatedBy()).isEqualTo("alice");
        assertThat(entity.getModifiedDate()).isEqualTo(now.plusSeconds(60));
        assertThat(entity.getModifiedBy()).isEqualTo("bob");
        assertThat(entity.getVersion()).isEqualTo("7");
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = AuditableEntity.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
