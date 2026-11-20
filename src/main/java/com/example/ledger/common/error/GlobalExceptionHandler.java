package com.example.ledger.common.error;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into RFC 7807 "problem details" JSON responses.
 *
 * <p>{@code @RestControllerAdvice} applies these handlers to every controller.
 * Extending {@link ResponseEntityExceptionHandler} gives us ready-made handling for
 * Spring MVC's own exceptions (malformed JSON, missing header, unsupported method...),
 * which also produce {@link ProblemDetail} bodies.
 *
 * <p>Example body:
 * <pre>
 * {
 *   "type": "/problems/insufficient-funds",
 *   "title": "Insufficient funds",
 *   "status": 422,
 *   "detail": "Account 7 has 500 paise, cannot debit 900 paise",
 *   "instance": "/api/transfers",
 *   "code": "INSUFFICIENT_FUNDS"
 * }
 * </pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** All of our deliberate business errors. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        problem.setType(problemType(ex.code()));
        problem.setTitle(ex.title());
        problem.setProperty("code", ex.code());
        return ResponseEntity.status(ex.status()).body(problem);
    }

    /**
     * A database constraint fired that the code did not check for first (or lost a race
     * on, e.g. two sign-ups with the same email at the same moment). The constraint kept
     * the data correct; we report a conflict instead of a 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(DataIntegrityViolationException ex) {
        log.warn("Database constraint violation: {}", ex.getMostSpecificCause().getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The request conflicts with existing data.");
        problem.setType(problemType("DATA_CONFLICT"));
        problem.setTitle("Data conflict");
        problem.setProperty("code", "DATA_CONFLICT");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /** Anything unexpected: log it with the stack trace, but never leak internals to the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred.");
        problem.setType(problemType("INTERNAL_ERROR"));
        problem.setTitle("Internal error");
        problem.setProperty("code", "INTERNAL_ERROR");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    /**
     * Bean Validation failed on a {@code @Valid @RequestBody}. The default handler already
     * builds a 400 ProblemDetail; we add a {@code code} and a per-field list of errors.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setType(problemType("VALIDATION_FAILED"));
        problem.setTitle("Validation failed");
        problem.setDetail("One or more fields are invalid.");
        problem.setProperty("code", "VALIDATION_FAILED");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toFieldError)
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.status(status).headers(headers).body(problem);
    }

    private static Map<String, String> toFieldError(FieldError error) {
        String message = error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage();
        return Map.of("field", error.getField(), "message", message);
    }

    /** INSUFFICIENT_FUNDS -> /problems/insufficient-funds */
    static URI problemType(String code) {
        return URI.create("/problems/" + code.toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}
