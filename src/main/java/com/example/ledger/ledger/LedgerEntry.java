package com.example.ledger.ledger;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * One line in the double-entry ledger: "account X changed by amount Y because of transfer Z".
 *
 * <p>{@code @Immutable} tells Hibernate never to issue an UPDATE for this entity, and the
 * database trigger in V1 rejects UPDATE/DELETE as well. Entries are only ever inserted.
 */
@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private Long transferId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    /** Signed: negative = money left the account, positive = money arrived. */
    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
        // for JPA
    }

    LedgerEntry(long transferId, long accountId, long amountPaise, Instant createdAt) {
        if (amountPaise == 0) {
            throw new IllegalArgumentException("ledger entry amount must not be zero");
        }
        this.transferId = transferId;
        this.accountId = accountId;
        this.amountPaise = amountPaise;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getTransferId() {
        return transferId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
