package com.example.ledger.account;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A wallet holding INR, in paise.
 *
 * <p>{@code balancePaise} is a cached total: the ledger entries are the source of truth,
 * and invariant 4 says the two must always agree. The balance can only change through
 * {@link #debit(long)} and {@link #credit(long)}, which enforce the no-negative rule.
 *
 * <p>We keep the owner as a plain {@code ownerId} column rather than a {@code @ManyToOne User}
 * association. That keeps loading explicit (no lazy proxies, no surprise extra queries) and
 * the account aggregate small.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null only for the FUNDING account. */
    @Column(name = "owner_id")
    private Long ownerId;

    @Enumerated(EnumType.STRING) // store "USER", not the ordinal 0 (ordinals break when the enum is reordered)
    @Column(name = "kind", nullable = false, length = 16)
    private AccountKind kind;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "balance_paise", nullable = false)
    private long balancePaise;

    /**
     * Optimistic-locking version. Hibernate adds {@code AND version = ?} to every UPDATE of
     * this row and increments it. If another transaction changed the row since we read it,
     * the UPDATE matches 0 rows and Hibernate throws an optimistic-lock exception.
     * Null means "not saved yet", which is how Spring Data's save() knows to INSERT.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
        // for JPA
    }

    private Account(Long ownerId, AccountKind kind, String currency) {
        this.ownerId = ownerId;
        this.kind = kind;
        this.currency = currency;
        this.balancePaise = 0;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** A new, empty INR wallet for a user. */
    public static Account openUserWallet(long ownerId) {
        return new Account(ownerId, AccountKind.USER, "INR");
    }

    /** A new, empty wallet for the race demo, owned by the demo user. */
    public static Account openDemoWallet(long demoOwnerId) {
        return new Account(demoOwnerId, AccountKind.DEMO, "INR");
    }

    /**
     * Takes money out. Throws {@link InsufficientFundsException} if a non-funding account
     * would go negative. {@code Math.subtractExact} throws on overflow instead of silently
     * wrapping around to a huge positive number.
     */
    public void debit(long amountPaise) {
        requirePositive(amountPaise);
        long newBalance = Math.subtractExact(balancePaise, amountPaise);
        if (newBalance < 0 && !mayGoNegative()) {
            throw new InsufficientFundsException(id, balancePaise, amountPaise);
        }
        this.balancePaise = newBalance;
    }

    /** Puts money in. */
    public void credit(long amountPaise) {
        requirePositive(amountPaise);
        this.balancePaise = Math.addExact(balancePaise, amountPaise);
    }

    /** Only the system funding account may have a negative balance (invariant 2). */
    public boolean mayGoNegative() {
        return kind == AccountKind.FUNDING;
    }

    /** Would debiting this amount break invariant 2? Used by code that writes balances itself. */
    public boolean canDebit(long amountPaise) {
        return mayGoNegative() || balancePaise >= amountPaise;
    }

    private static void requirePositive(long amountPaise) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("amount must be positive, was " + amountPaise);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public AccountKind getKind() {
        return kind;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalancePaise() {
        return balancePaise;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
