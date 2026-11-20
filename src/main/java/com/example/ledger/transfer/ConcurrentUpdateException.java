package com.example.ledger.transfer;

import com.example.ledger.common.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 409: the transfer kept colliding with concurrent transfers on the same account and gave up
 * after the configured number of attempts. Nothing was moved; the client may retry with the
 * same Idempotency-Key.
 */
public class ConcurrentUpdateException extends ApiException {

    public ConcurrentUpdateException(String detail) {
        super(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "Concurrent update", detail);
    }
}
