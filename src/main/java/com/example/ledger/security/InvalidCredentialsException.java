package com.example.ledger.security;

import com.example.ledger.common.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 401 for a failed login. The message is identical for "no such email" and "wrong password",
 * so the endpoint can't be used to discover which emails are registered.
 */
public class InvalidCredentialsException extends ApiException {

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials",
                "Email or password is incorrect");
    }
}
