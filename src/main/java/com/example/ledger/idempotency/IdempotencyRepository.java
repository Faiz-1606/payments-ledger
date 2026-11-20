package com.example.ledger.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {

    /**
     * Atomically claims a key. Returns 1 if this transaction inserted the row (we own the key),
     * 0 if the key already existed.
     *
     * <p>If another <em>uncommitted</em> transaction already inserted the same key, PostgreSQL
     * makes this statement wait until that transaction commits (then we get 0) or rolls back
     * (then our insert goes through and we get 1). This is how two concurrent requests
     * with the same key are serialized, by the database rather than by Java code.
     *
     * <p>{@code @Modifying} is required for any {@code @Query} that changes data.
     */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_keys (idempotency_key, request_hash, created_at)
            VALUES (:key, :requestHash, now())
            ON CONFLICT (idempotency_key) DO NOTHING""", nativeQuery = true)
    int tryInsert(@Param("key") String key, @Param("requestHash") String requestHash);
}
