package com.example.ledger.transfer.locking;

/**
 * The part of a transfer that differs between concurrency-control strategies: how the two
 * accounts are read, and how their new balances are written.
 *
 * <p>Everything else (idempotency, eligibility checks, the transfer row, the ledger entries)
 * is shared and lives in {@code TransferService}. Both methods are called inside the same
 * database transaction, which {@code TransferService} opens (and, for strategies with
 * {@link #maxAttempts()} &gt; 1, retries).
 */
public interface LockingStrategy {

    LockingMode mode();

    /** Loads both accounts. Throws {@code NotFoundException} if either does not exist. */
    AccountPair loadAccounts(long fromAccountId, long toAccountId);

    /**
     * Moves {@code amountPaise} from {@code accounts.from()} to {@code accounts.to()} in the
     * accounts table. Throws {@code InsufficientFundsException} if the source may not go negative
     * and lacks the funds.
     */
    void moveMoney(AccountPair accounts, long amountPaise);

    /** How many times the whole transaction may be attempted. Only optimistic locking retries. */
    default int maxAttempts() {
        return 1;
    }
}
