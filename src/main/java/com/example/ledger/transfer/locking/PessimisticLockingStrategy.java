package com.example.ledger.transfer.locking;

import org.springframework.stereotype.Component;

import com.example.ledger.account.Account;
import com.example.ledger.account.AccountRepository;
import com.example.ledger.common.error.NotFoundException;

/**
 * Lock first, then read and write: {@code SELECT ... FOR UPDATE} on both accounts.
 *
 * <p>A row lock is held until the transaction commits or rolls back. A second transfer that
 * touches either account waits at its {@code SELECT ... FOR UPDATE} until the first one
 * finishes, and then reads the <em>new</em> balance. There are no lost updates and nothing to
 * retry; the cost is waiting.
 *
 * <p><b>Lock ordering prevents deadlocks.</b> If T1 locks A then B while T2 locks B then A,
 * each waits for the other forever (PostgreSQL detects this after {@code deadlock_timeout},
 * 1s by default, and kills one). Always locking the lower id first means every transaction
 * acquires locks in the same global order, so a wait cycle cannot form.
 */
@Component
public class PessimisticLockingStrategy implements LockingStrategy {

    private final AccountRepository accountRepository;

    public PessimisticLockingStrategy(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public LockingMode mode() {
        return LockingMode.PESSIMISTIC;
    }

    @Override
    public AccountPair loadAccounts(long fromAccountId, long toAccountId) {
        long firstId = Math.min(fromAccountId, toAccountId);
        long secondId = Math.max(fromAccountId, toAccountId);
        Account first = lock(firstId);
        Account second = lock(secondId);
        return firstId == fromAccountId ? new AccountPair(first, second) : new AccountPair(second, first);
    }

    /**
     * The rows are locked, so the entities hold the current balances. The changes are written
     * by dirty checking at commit, while we still hold the locks.
     */
    @Override
    public void moveMoney(AccountPair accounts, long amountPaise) {
        accounts.from().debit(amountPaise);
        accounts.to().credit(amountPaise);
    }

    private Account lock(long accountId) {
        return accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> NotFoundException.account(accountId));
    }
}
