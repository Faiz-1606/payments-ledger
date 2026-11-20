package com.example.ledger.transfer;

/**
 * Transfers here are synchronous: they either commit (COMPLETED) or roll back entirely and
 * leave no row. The column exists so that asynchronous flows (e.g. a payout to a bank that
 * settles later: PENDING -> COMPLETED/FAILED) can be added without redesigning the table.
 */
public enum TransferStatus {
    COMPLETED
}
