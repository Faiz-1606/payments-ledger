package com.example.ledger.user;

import com.example.ledger.common.error.ApiException;
import org.springframework.http.HttpStatus;

public class EmailAlreadyRegisteredException extends ApiException {

    public EmailAlreadyRegisteredException(String email) {
        super(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "Email already registered",
                "A user with email " + email + " already exists");
    }
}
