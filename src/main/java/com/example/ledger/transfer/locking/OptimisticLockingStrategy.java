package com.example.ledger.transfer.locking;

import org.springframework.stereotype.Component;

import com.example.ledger.account.Account;
import com.example.ledger.account.AccountRepository;
import com.example.ledger.common.error.NotFoundException;

/**
 * Read without locking, then write only if nobody else wrote in between.
 *
 * <p>{@code Account.version} is annotated with {@code @Version}, so Hibernate turns the balance
 * change into:
 * <pre>
 *   UPDATE accounts SET balance_paise = ?, version = 8 WHERE id = ? AND version = 7
 * </pre>
 * If another transaction committed a change first, the version is no longer 7, the UPDATE
 * matches 0 rows, and Hibernate throws an optimistic-lock exception. The whole transaction rolls
 * back, and {@code TransferService} retries it from scratch (re-reading fresh balances) with
 * jittered exponential backoff, up to {@link #maxAttempts()} times.
 *
 * <p>Good when conflicts are rare (no waiting, no lock held while thinking). Bad on hot
 * accounts, where most attempts collide and the work is repeated.
 */
@Component
public class OptimisticLockingStrategy implements LockingStrategy {

    private final AccountRepository accountRepository;
    private final int maxAttempts;

    public OptimisticLockingStrategy(AccountRepository accountRepository, LockingProperties properties) {
        this.accountRepository = accountRepository;
        this.maxAttempts = properties.optimistic().maxAttempts();
    }

    @Override
    public LockingMode mode() {
        return LockingMode.OPTIMISTIC;
    }

    @Override
    public AccountPair loadAccounts(long fromAccountId, long toAccountId) {
        Account from = accountRepository.findById(fromAccountId)
                .orElseThrow(() -> NotFoundException.account(fromAccountId));
        Account to = accountRepository.findById(toAccountId)
                .orElseThrow(() -> NotFoundException.account(toAccountId));
        return new AccountPair(from, to);
    }

    /**
     * Changes the managed entities and flushes immediately, so a version conflict surfaces here
     * as Spring's {@code ObjectOptimisticLockingFailureException} rather than later at commit.
     * Flushing through the repository (not the raw EntityManager) gets Spring's exception
     * translation.
     */
    @Override
    public void moveMoney(AccountPair accounts, long amountPaise) {
        accounts.from().debit(amountPaise);
        accounts.to().credit(amountPaise);
        accountRepository.flush();
    }

    @Override
    public int maxAttempts() {
        return maxAttempts;
    }
}
