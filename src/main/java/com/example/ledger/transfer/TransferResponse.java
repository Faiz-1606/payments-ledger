package com.example.ledger.transfer;

import java.time.Instant;

/** JSON returned for a transfer or deposit. */
public record TransferResponse(Long id, TransferType type, TransferStatus status, Long fromAccountId,
                               Long toAccountId, long amountPaise, Instant createdAt) {

    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(transfer.getId(), transfer.getType(), transfer.getStatus(),
                transfer.getFromAccountId(), transfer.getToAccountId(), transfer.getAmountPaise(),
                transfer.getCreatedAt());
    }
}
