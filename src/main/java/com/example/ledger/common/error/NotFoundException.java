package com.example.ledger.common.error;

import org.springframework.http.HttpStatus;

/** 404 for a resource that does not exist. */
public class NotFoundException extends ApiException {

    private NotFoundException(String code, String title, String detail) {
        super(HttpStatus.NOT_FOUND, code, title, detail);
    }

    public static NotFoundException account(long accountId) {
        return new NotFoundException("ACCOUNT_NOT_FOUND", "Account not found",
                "Account " + accountId + " does not exist");
    }

    public static NotFoundException user(long userId) {
        return new NotFoundException("USER_NOT_FOUND", "User not found",
                "User " + userId + " does not exist");
    }

    public static NotFoundException transfer(long transferId) {
        return new NotFoundException("TRANSFER_NOT_FOUND", "Transfer not found",
                "Transfer " + transferId + " does not exist");
    }
}
