package com.example.ledger.common.error;

import org.springframework.http.HttpStatus;

/**
 * 403: the caller is authenticated but not allowed to touch this resource (e.g. someone
 * else's account). We return 403 rather than pretending the resource does not exist (404);
 * see docs/DECISIONS.md.
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String detail) {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN", "Forbidden", detail);
    }
}
