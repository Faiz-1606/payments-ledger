package com.example.ledger.transfer;

/**
 * Internal, already-validated description of a money movement. Controllers build it from
 * HTTP requests; the demo and tests build it directly.
 */
public record TransferCommand(TransferType type, long fromAccountId, long toAccountId, long amountPaise) {

    public TransferCommand {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("amountPaise must be positive");
        }
    }
}
