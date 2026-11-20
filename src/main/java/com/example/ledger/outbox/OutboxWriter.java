package com.example.ledger.outbox;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adds events to the outbox <em>inside</em> the caller's transaction.
 *
 * <p>This writes the outbox row on every path, Kafka or not. In the {@code core} profile
 * nothing publishes them, so they just accumulate; turning a publisher on later would deliver
 * them all. That is the outbox guarantee: an event exists if and only if its transfer committed.
 */
@Component
public class OutboxWriter {

    private final EntityManager entityManager;
    private final JsonMapper jsonMapper;

    public OutboxWriter(EntityManager entityManager, JsonMapper jsonMapper) {
        this.entityManager = entityManager;
        this.jsonMapper = jsonMapper;
    }

    /**
     * {@code MANDATORY}: an outbox row written in its own transaction would defeat the purpose.
     *
     * <p>We call {@code entityManager.persist} rather than a Spring Data {@code save}: the id is
     * assigned by us, and {@code save()} on an entity that already has an id assumes it might
     * exist and does a {@code merge} (an extra SELECT before the INSERT).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TransferCompletedEvent transferCompleted(long transferId, String transferType, long fromAccountId,
                                                    long toAccountId, long amountPaise, Instant occurredAt) {
        TransferCompletedEvent event = new TransferCompletedEvent(UUID.randomUUID(), TransferCompletedEvent.TYPE,
                transferId, transferType, fromAccountId, toAccountId, amountPaise, occurredAt);
        entityManager.persist(new OutboxEvent(event.eventId(), "Transfer", Long.toString(transferId),
                event.eventType(), jsonMapper.writeValueAsString(event), occurredAt));
        return event;
    }
}
