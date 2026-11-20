package com.example.ledger.transfer;

public enum TransferType {
    /** Funding account -> user wallet (money enters the system). */
    DEPOSIT,
    /** User wallet -> user wallet. */
    TRANSFER
}
