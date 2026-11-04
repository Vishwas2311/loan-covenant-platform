package com.crisil.covenant.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import com.crisil.covenant.dto.ErrorResponse;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsApiExceptionToItsStatusAndCode() {
        ResponseEntity<ErrorResponse> response = handler.handleApi(
                new ResourceNotFoundException("missing"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(response.getBody().message()).isEqualTo("missing");
    }

    @Test
    void mapsDuplicateResourceTo409() {
        ResponseEntity<ErrorResponse> response = handler.handleApi(
                new DuplicateResourceException("dup"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("DUPLICATE_RESOURCE");
    }

    @Test
    void mapsMethodArgumentNotValidWithFieldErrors() {
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("obj", "name", "must not be blank");
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().details()).hasSize(1);
        assertThat(response.getBody().details().get(0).field()).isEqualTo("name");
        assertThat(response.getBody().details().get(0).message()).isEqualTo("must not be blank");
    }

    @Test
    void mapsMethodArgumentNotValidWithNullMessageToInvalid() {
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("obj", "name", null, false, null, null, null);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().details().get(0).message()).isEqualTo("invalid");
    }

    @Test
    void mapsConstraintViolation() {
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("create.request.name");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must not be blank");
        ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

        ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().details()).hasSize(1);
    }

    @Test
    void mapsIllegalArgument() {
        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgument(
                new IllegalArgumentException("bad arg"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("ILLEGAL_ARGUMENT");
        assertThat(response.getBody().message()).isEqualTo("bad arg");
    }

    @Test
    void mapsIllegalArgumentWithNullMessage() {
        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgument(
                new IllegalArgumentException());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Illegal argument");
    }

    @Test
    void mapsUnexpectedExceptionTo500() {
        ResponseEntity<ErrorResponse> response = handler.handleUnexpected(
                new RuntimeException("boom"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
    }

    @Test
    void keepsSpringClientErrorStatusInsteadOf500() {
        ResponseEntity<ErrorResponse> notFound = handler.handleUnexpected(
                new org.springframework.web.servlet.resource.NoResourceFoundException(
                        org.springframework.http.HttpMethod.GET, "api/v1/nope"));
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getBody().code()).isEqualTo("NOT_FOUND");

        ResponseEntity<ErrorResponse> method = handler.handleUnexpected(
                new org.springframework.web.HttpRequestMethodNotSupportedException("PATCH"));
        assertThat(method.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);

        ResponseEntity<ErrorResponse> unreadable = handler.handleUnreadable(
                new org.springframework.http.converter.HttpMessageNotReadableException("bad", 
                        new org.springframework.mock.http.MockHttpInputMessage(new byte[0])));
        assertThat(unreadable.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unreadable.getBody().code()).isEqualTo("MALFORMED_REQUEST");

        ResponseEntity<ErrorResponse> boom = handler.handleUnexpected(new IllegalStateException("x"));
        assertThat(boom.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void aWrongParameterTypeIs400NamingTheParameter() throws Exception {
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex =
                new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException("yesterday",
                        java.time.LocalDate.class, "from", null, new IllegalArgumentException("bad"));
        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatch(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("Parameter 'from' must be a date (yyyy-MM-dd)");
        assertThat(handler.handleTypeMismatch(new org.springframework.web.method.annotation
                .MethodArgumentTypeMismatchException("x", Integer.class, "page", null, null)).getBody().message())
                .isEqualTo("Parameter 'page' must be a Integer");
    }

    @Test
    void aClientThatWentAwayGetsNoBodyAndNoErrorLog() {
        assertThat(handler.handleClientGone(new org.springframework.web.context.request.async
                .AsyncRequestNotUsableException("Broken pipe"))).isNull();
    }
}
