package com.example.ledger.transfer;

/**
 * Result of a transfer or deposit request.
 *
 * @param replayed true if this is a stored response for an Idempotency-Key that was already
 *                 used (no money moved this time)
 * @param attempts how many times the transaction ran (more than 1 only after optimistic-lock
 *                 conflicts); used by metrics and the race demo, never sent to clients
 */
public record TransferOutcome(TransferResponse response, boolean replayed, int attempts) {
}
