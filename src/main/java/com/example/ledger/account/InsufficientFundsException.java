package com.example.ledger.account;

import com.example.ledger.common.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 422 Unprocessable Content: the request is well-formed, but the account's current state
 * does not allow it.
 */
public class InsufficientFundsException extends ApiException {

    public InsufficientFundsException(Long accountId, long balancePaise, long requestedPaise) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_FUNDS", "Insufficient funds",
                "Account " + accountId + " has " + balancePaise + " paise, cannot debit "
                        + requestedPaise + " paise");
    }
}
