package com.example.ledger.transfer.locking;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.example.ledger.account.Account;
import com.example.ledger.account.AccountRepository;
import com.example.ledger.account.InsufficientFundsException;
import com.example.ledger.common.error.NotFoundException;

/**
 * DELIBERATELY BROKEN. Plain read-modify-write with no locking, to demonstrate the
 * <b>lost update</b> anomaly. Never use it for real money.
 *
 * <pre>
 *   T1: SELECT balance -> 1000           T2: SELECT balance -> 1000
 *   T1: UPDATE balance = 1000 - 100      (T2 waits for T1's row lock)
 *   T1: COMMIT                           T2: UPDATE balance = 1000 - 300   &lt;- overwrites T1
 *                                        T2: COMMIT
 *   Final balance 700, but the ledger says 1000 - 100 - 300 = 600. T1's debit is lost.
 * </pre>
 *
 * Both transactions also passed the funds check on the stale value 1000, so between them they
 * can spend more than the account ever held: the ledger-derived balance can go negative
 * while the stored balance (protected by the CHECK constraint) never does.
 *
 * <p>Balances are written with plain JDBC {@code UPDATE ... SET balance_paise = ?} (a "blind
 * write" of a value computed in Java), bypassing the {@code @Version} check that JPA would
 * add. That is exactly what hand-written DAO code often does. Writes are issued in ascending
 * id order only so that this strategy shows one bug (lost updates) rather than also
 * deadlocking.
 */
@Component
public class NaiveLockingStrategy implements LockingStrategy {

    private final AccountRepository accountRepository;
    private final JdbcTemplate jdbc;

    public NaiveLockingStrategy(AccountRepository accountRepository, JdbcTemplate jdbc) {
        this.accountRepository = accountRepository;
        this.jdbc = jdbc;
    }

    @Override
    public LockingMode mode() {
        return LockingMode.NAIVE;
    }

    /** Plain {@code SELECT}: no lock, and nothing stops another transaction changing the row. */
    @Override
    public AccountPair loadAccounts(long fromAccountId, long toAccountId) {
        Account from = accountRepository.findById(fromAccountId)
                .orElseThrow(() -> NotFoundException.account(fromAccountId));
        Account to = accountRepository.findById(toAccountId)
                .orElseThrow(() -> NotFoundException.account(toAccountId));
        return new AccountPair(from, to);
    }

    @Override
    public void moveMoney(AccountPair accounts, long amountPaise) {
        Account from = accounts.from();
        Account to = accounts.to();
        // Check and compute from values that may already be stale.
        if (!from.canDebit(amountPaise)) {
            throw new InsufficientFundsException(from.getId(), from.getBalancePaise(), amountPaise);
        }
        long newFromBalance = Math.subtractExact(from.getBalancePaise(), amountPaise);
        long newToBalance = Math.addExact(to.getBalancePaise(), amountPaise);

        // Blind writes. Whatever other transactions did since our SELECT is overwritten.
        if (from.getId() < to.getId()) {
            overwriteBalance(from.getId(), newFromBalance);
            overwriteBalance(to.getId(), newToBalance);
        } else {
            overwriteBalance(to.getId(), newToBalance);
            overwriteBalance(from.getId(), newFromBalance);
        }
    }

    private void overwriteBalance(long accountId, long newBalance) {
        jdbc.update("UPDATE accounts SET balance_paise = ? WHERE id = ?", newBalance, accountId);
    }
}
