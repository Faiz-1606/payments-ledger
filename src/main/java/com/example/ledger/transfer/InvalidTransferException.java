package com.example.ledger.transfer;

import com.example.ledger.common.error.ApiException;
import org.springframework.http.HttpStatus;

/** 400: the transfer request breaks a rule that does not depend on balances. */
public class InvalidTransferException extends ApiException {

    private InvalidTransferException(String code, String title, String detail) {
        super(HttpStatus.BAD_REQUEST, code, title, detail);
    }

    public static InvalidTransferException sameAccount(long accountId) {
        return new InvalidTransferException("SAME_ACCOUNT_TRANSFER", "Same-account transfer",
                "Cannot transfer from account " + accountId + " to itself");
    }

    public static InvalidTransferException notEligible(long accountId, String reason) {
        return new InvalidTransferException("ACCOUNT_NOT_ELIGIBLE", "Account not eligible",
                "Account " + accountId + " " + reason);
    }

    public static InvalidTransferException currencyMismatch(long fromId, long toId) {
        return new InvalidTransferException("CURRENCY_MISMATCH", "Currency mismatch",
                "Accounts " + fromId + " and " + toId + " hold different currencies");
    }
}
