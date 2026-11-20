package com.example.ledger.transfer;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A completed movement of money from one account to another. Never modified after insert. */
@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", length = 100, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16, updatable = false)
    private TransferType type;

    @Column(name = "from_account_id", nullable = false, updatable = false)
    private Long fromAccountId;

    @Column(name = "to_account_id", nullable = false, updatable = false)
    private Long toAccountId;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TransferStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Transfer() {
        // for JPA
    }

    public Transfer(String idempotencyKey, TransferType type, long fromAccountId, long toAccountId,
                    long amountPaise, Instant createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.type = type;
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amountPaise = amountPaise;
        this.status = TransferStatus.COMPLETED;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public TransferType getType() {
        return type;
    }

    public Long getFromAccountId() {
        return fromAccountId;
    }

    public Long getToAccountId() {
        return toAccountId;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
