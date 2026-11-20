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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
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

import com.example.ledger.observability.RequestIdFilter;

/**
 * Turns exceptions into RFC 7807 "problem details" JSON responses.
 *
 * <p>{@code @RestControllerAdvice} applies these handlers to every controller (and, through
 * {@code ProblemDetailsSecurityHandlers}, to security failures too). Extending
 * {@link ResponseEntityExceptionHandler} gives ready-made handling for Spring MVC's own
 * exceptions (malformed JSON, missing header, unsupported method...). Every body carries a stable
 * {@code code} that clients can switch on, and the {@code requestId} that appears in the logs.
 *
 * <p>Example body:
 * <pre>
 * {
 *   "type": "/problems/insufficient-funds",
 *   "title": "Insufficient funds",
 *   "status": 422,
 *   "detail": "Account 7 has 500 paise, cannot debit 900 paise",
 *   "instance": "/api/transfers",
 *   "code": "INSUFFICIENT_FUNDS",
 *   "requestId": "1f0c6e3a-..."
 * }
 * </pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** All of our deliberate business errors. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        return problem(ex.status(), ex.code(), ex.title(), ex.getMessage());
    }

    /**
     * No token, or a token that failed validation (bad signature, expired, wrong issuer).
     * Reached via {@code ProblemDetailsSecurityHandlers} from the security filter chain.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        String detail = ex instanceof OAuth2AuthenticationException
                ? "The access token is invalid or expired."
                : "Authentication is required: send Authorization: Bearer <token>.";
        return problem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Unauthorized", detail);
    }

    /** Authenticated, but the role is not enough (e.g. a USER calling an ADMIN endpoint). */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "Forbidden",
                "You do not have permission to perform this operation.");
    }

    /**
     * The database gave up on a lock: a deadlock victim, or a lock or serialization timeout.
     * Nothing was committed, so the client can safely retry with the same Idempotency-Key.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ProblemDetail> handleConcurrencyFailure(ConcurrencyFailureException ex) {
        log.warn("Concurrency failure: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "Concurrent update",
                "The request collided with a concurrent update. Retry with the same Idempotency-Key.");
    }

    /**
     * A database constraint fired that the code did not check for first (or lost a race
     * on, e.g. two sign-ups with the same email at the same moment). The constraint kept
     * the data correct; we report a conflict instead of a 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(DataIntegrityViolationException ex) {
        log.warn("Database constraint violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "DATA_CONFLICT", "Data conflict",
                "The request conflicts with existing data.");
    }

    /** Anything unexpected: log it with the stack trace, but never leak internals to the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error",
                "An unexpected error occurred.");
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
     * so we resolve the ProblemDetail ourselves and add {@code code} and {@code requestId}.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body == null && ex instanceof ErrorResponse errorResponse) {
            body = errorResponse.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
        }
        if (body instanceof ProblemDetail problem) {
            if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
                problem.setProperty("code", codeFor(ex, statusCode));
            }
            addRequestId(problem);
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(problemType(code));
        problem.setTitle(title);
        problem.setProperty("code", code);
        addRequestId(problem);
        return ResponseEntity.status(status).body(problem);
    }

    private static ProblemDetail validationProblem(ProblemDetail problem, List<Map<String, String>> errors) {
        problem.setType(problemType("VALIDATION_FAILED"));
        problem.setTitle("Validation failed");
        problem.setDetail("One or more fields are invalid.");
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("errors", errors);
        addRequestId(problem);
        return problem;
    }

    private static void addRequestId(ProblemDetail problem) {
        String requestId = RequestIdFilter.currentRequestId();
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
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
