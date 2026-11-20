package com.example.ledger.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * The event published to Kafka when a transfer or deposit commits. This record is the public
 * contract between producer and consumers: add fields freely, but never rename or remove one
 * that consumers read.
 *
 * @param eventId unique per event (not per transfer); consumers deduplicate on it
 */
public record TransferCompletedEvent(
        UUID eventId,
        String eventType,
        long transferId,
        String transferType,
        long fromAccountId,
        long toAccountId,
        long amountPaise,
        Instant occurredAt) {

    public static final String TYPE = "TransferCompleted";
}
