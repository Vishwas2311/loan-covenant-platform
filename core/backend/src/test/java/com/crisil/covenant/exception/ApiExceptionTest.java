package com.crisil.covenant.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApiExceptionTest {

    @Test
    void exposesStatusCodeAndMessage() {
        ApiException ex = new ApiException(HttpStatus.I_AM_A_TEAPOT, "TEA", "teapot");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.I_AM_A_TEAPOT);
        assertThat(ex.getCode()).isEqualTo("TEA");
        assertThat(ex.getMessage()).isEqualTo("teapot");
    }

    @Test
    void resourceNotFoundIs404() {
        ResourceNotFoundException ex = new ResourceNotFoundException("nope");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getCode()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void duplicateResourceIs409() {
        DuplicateResourceException ex = new DuplicateResourceException("dup");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getCode()).isEqualTo("DUPLICATE_RESOURCE");
    }
}
