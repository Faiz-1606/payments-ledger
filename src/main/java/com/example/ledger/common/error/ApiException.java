package com.example.ledger.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base class for every business error the API reports on purpose.
 *
 * <p>Each subclass carries the HTTP status, a stable machine-readable {@code code}
 * (clients switch on this, never on the English message) and a short title.
 * {@link GlobalExceptionHandler} turns it into an RFC 7807 problem response.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;

    protected ApiException(HttpStatus status, String code, String title, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    /** Stable error code such as {@code INSUFFICIENT_FUNDS}. */
    public String code() {
        return code;
    }

    public String title() {
        return title;
    }
}
