package com.example.ledger.account;

public enum AccountKind {
    /** A customer wallet. Balance can never go below zero. */
    USER,
    /** The single system account deposits come from. Its balance may go negative. */
    FUNDING,
    /** A wallet owned by the race demo (demo profile). Can only transact with other DEMO wallets. */
    DEMO
}
