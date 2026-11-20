package com.example.ledger.user;

public enum Role {
    /** A wallet holder: can see and move only their own money. */
    USER,
    /** Operations staff: can make deposits and run reconciliation. */
    ADMIN
}
