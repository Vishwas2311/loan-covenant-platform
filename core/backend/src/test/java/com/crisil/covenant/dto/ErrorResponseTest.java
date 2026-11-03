package com.crisil.covenant.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ErrorResponseTest {

    @Test
    void buildsBareEnvelope() {
        ErrorResponse r = ErrorResponse.of("CODE", "msg");
        assertThat(r.code()).isEqualTo("CODE");
        assertThat(r.message()).isEqualTo("msg");
        assertThat(r.details()).isEmpty();
        assertThat(r.timestamp()).isNotNull();
    }

    @Test
    void buildsWithDetails() {
        List<ErrorResponse.FieldError> details = List.of(new ErrorResponse.FieldError("name", "required"));
        ErrorResponse r = ErrorResponse.of("X", "y", details);
        assertThat(r.details()).hasSize(1);
        assertThat(r.details().get(0).field()).isEqualTo("name");
        assertThat(r.details().get(0).message()).isEqualTo("required");
    }
}
