package com.example.ledger.idempotency;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A stored Idempotency-Key. Rows are created with a native {@code INSERT ... ON CONFLICT DO
 * NOTHING} (see {@link IdempotencyRepository#tryInsert}); this entity is used to read them and
 * to record the response.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    @Id
    @Column(name = "idempotency_key", length = 100)
    private String key;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "transfer_id")
    private Long transferId;

    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
        // for JPA
    }

    /** Called once, inside the transaction that inserted the row, after the money moved. */
    void complete(long transferId, String responseBody) {
        this.transferId = transferId;
        this.responseBody = responseBody;
    }

    public String getKey() {
        return key;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Long getTransferId() {
        return transferId;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
