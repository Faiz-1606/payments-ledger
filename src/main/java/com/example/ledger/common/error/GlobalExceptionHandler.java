package com.example.ledger.common.error;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.MethodParameter;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into RFC 7807 "problem details" JSON responses.
 *
 * <p>{@code @RestControllerAdvice} applies these handlers to every controller.
 * Extending {@link ResponseEntityExceptionHandler} gives us ready-made handling for
 * Spring MVC's own exceptions (malformed JSON, missing header, unsupported method...),
 * which also produce {@link ProblemDetail} bodies. Every response gets a stable
 * {@code code} property that clients can switch on.
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

    /**
     * The database gave up on a lock: a deadlock victim, or a lock or serialization timeout.
     * Nothing was committed, so the client can safely retry with the same Idempotency-Key.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ProblemDetail> handleConcurrencyFailure(ConcurrencyFailureException ex) {
        log.warn("Concurrency failure: {}", ex.getMostSpecificCause().getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The request collided with a concurrent update. Retry with the same Idempotency-Key.");
        problem.setType(problemType("CONCURRENT_UPDATE"));
        problem.setTitle("Concurrent update");
        problem.setProperty("code", "CONCURRENT_UPDATE");
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
     * {@code @Valid @RequestBody} failed and the controller method has no other constraint
     * annotations. Lists every invalid field.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(fieldError -> errors.add(toError(fieldError)));
        return ResponseEntity.status(status).headers(headers).body(validationProblem(ex.getBody(), errors));
    }

    /**
     * Since Spring 6.1, when a controller method has constraint annotations on its parameters
     * (e.g. {@code @Pattern} on the Idempotency-Key header), Spring validates the <em>whole
     * method</em> at once, including the {@code @Valid} body, and reports everything in a single
     * {@link HandlerMethodValidationException}. We produce the same shape as above.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors bodyErrors) {
                bodyErrors.getFieldErrors().forEach(fieldError -> errors.add(toError(fieldError)));
            } else {
                String name = parameterName(result.getMethodParameter());
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    errors.add(Map.of("field", name, "message", messageOf(error)));
                }
            }
        }
        return ResponseEntity.status(status).headers(headers).body(validationProblem(ex.getBody(), errors));
    }

    /**
     * Every other Spring MVC exception (missing header, malformed JSON, wrong HTTP method...)
     * passes through here. The base class passes {@code body == null} and fills it in later,
     * so we resolve the ProblemDetail ourselves and add a {@code code}.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body == null && ex instanceof ErrorResponse errorResponse) {
            body = errorResponse.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
        }
        if (body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            problem.setProperty("code", codeFor(ex, statusCode));
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static ProblemDetail validationProblem(ProblemDetail problem, List<Map<String, String>> errors) {
        problem.setType(problemType("VALIDATION_FAILED"));
        problem.setTitle("Validation failed");
        problem.setDetail("One or more fields are invalid.");
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static String codeFor(Exception ex, HttpStatusCode statusCode) {
        if (ex instanceof MissingRequestHeaderException) {
            return "MISSING_HEADER";
        }
        if (ex instanceof HttpMessageNotReadableException) {
            return "MALFORMED_REQUEST";
        }
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status != null ? status.name() : "ERROR";
    }

    private static Map<String, String> toError(FieldError error) {
        return Map.of("field", error.getField(), "message", messageOf(error));
    }

    private static String messageOf(MessageSourceResolvable error) {
        return error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage();
    }

    /** For a header parameter report the header name (e.g. "Idempotency-Key"), else the Java name. */
    private static String parameterName(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.value().isEmpty()) {
            return header.value();
        }
        String name = parameter.getParameterName();
        return name != null ? name : "parameter" + parameter.getParameterIndex();
    }

    /** INSUFFICIENT_FUNDS -> /problems/insufficient-funds */
    static URI problemType(String code) {
        return URI.create("/problems/" + code.toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}
