package com.example.ledger.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims the oldest unpublished events for this publisher.
     *
     * <p>{@code FOR UPDATE} row-locks them until our transaction ends; {@code SKIP LOCKED} makes
     * any other publisher (another app instance) skip rows already claimed instead of waiting.
     * N instances can therefore drain the outbox in parallel without sending the same row
     * at the same time.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<OutboxEvent> lockNextUnpublished(@Param("limit") int limit);

    long countByPublishedAtIsNull();

    long countByPublishedAtIsNotNull();

    @Query("select min(e.createdAt) from OutboxEvent e where e.publishedAt is null")
    Optional<Instant> oldestUnpublishedCreatedAt();
}
