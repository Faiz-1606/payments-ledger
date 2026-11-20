package com.example.ledger.notification;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /**
     * Records that we processed this event. Returns 1 the first time, 0 for every duplicate.
     * Same technique as the Idempotency-Key table, now on the consumer side.
     */
    @Modifying
    @Query(value = """
            INSERT INTO processed_events (event_id, processed_at) VALUES (:eventId, now())
            ON CONFLICT (event_id) DO NOTHING""", nativeQuery = true)
    int tryMarkProcessed(@Param("eventId") UUID eventId);
}
