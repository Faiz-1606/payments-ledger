package com.example.ledger.idempotency;

import com.example.ledger.common.error.ApiException;
import org.springframework.http.HttpStatus;

/** Same Idempotency-Key, different request body: almost certainly a client bug. */
public class IdempotencyKeyReusedException extends ApiException {

    public IdempotencyKeyReusedException(String key) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED", "Idempotency key reused",
                "Idempotency-Key '" + key + "' was already used for a different request");
    }
}
