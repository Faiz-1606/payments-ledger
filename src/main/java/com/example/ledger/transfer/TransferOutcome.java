package com.example.ledger.transfer;

/**
 * Result of a transfer or deposit request.
 *
 * @param replayed true if this is a stored response for an Idempotency-Key that was already
 *                 used (no money moved this time)
 */
public record TransferOutcome(TransferResponse response, boolean replayed) {
}
