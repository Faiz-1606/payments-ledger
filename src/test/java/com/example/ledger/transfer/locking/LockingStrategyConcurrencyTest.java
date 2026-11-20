package com.example.ledger.transfer.locking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.ledger.account.InsufficientFundsException;
import com.example.ledger.support.AbstractIntegrationTest;
import com.example.ledger.transfer.ConcurrentUpdateException;
import com.example.ledger.transfer.TransferCommand;
import com.example.ledger.transfer.TransferOutcome;
import com.example.ledger.transfer.TransferService;
import com.example.ledger.transfer.TransferType;

/**
 * The heart of the project: hundreds of transfers fired in parallel at a few "hot" accounts.
 *
 * <p>Pessimistic and optimistic locking must keep every invariant. Naive mode must visibly
 * break them; if this test ever stops seeing naive lose updates, the test (not the
 * strategy) is too gentle.
 */
class LockingStrategyConcurrencyTest extends AbstractIntegrationTest {

    private static final int HOT_ACCOUNTS = 4;
    private static final int TRANSFERS = 400;
    private static final int THREADS = 32;
    private static final long OPENING_BALANCE = 1_000_000; // ₹10,000 per account
    private static final long MAX_AMOUNT = 10_000;          // up to ₹100 per transfer

    @Autowired
    TransferService transferService;

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = LockingMode.class, names = {"PESSIMISTIC", "OPTIMISTIC"})
    void safeStrategiesKeepEveryInvariantUnderContention(LockingMode mode) throws Exception {
        List<Long> accounts = openHotAccounts(HOT_ACCOUNTS);

        RaceStats stats = race(mode, randomTransfers(accounts, TRANSFERS, 42));
        System.out.printf("%s: %s%n", mode, stats);

        assertThat(stats.unexpected()).as("unexpected errors").isEmpty();
        if (mode == LockingMode.PESSIMISTIC) {
            assertThat(stats.retriesExhausted()).as("pessimistic never needs retries").isZero();
        }
        assertThat(stats.succeeded()).as("most transfers should go through").isGreaterThan(TRANSFERS / 2);

        invariants.assertAllHold();
        assertThat(sumOfBalances(accounts)).as("money is conserved").isEqualTo(HOT_ACCOUNTS * OPENING_BALANCE);
        assertThat(transferRowsOfType(TransferType.TRANSFER)).isEqualTo(stats.succeeded());
    }

    @Test
    void naiveStrategyLosesUpdatesAndBreaksInvariants() throws Exception {
        List<Long> accounts = openHotAccounts(HOT_ACCOUNTS);

        RaceStats stats = race(LockingMode.NAIVE, randomTransfers(accounts, TRANSFERS, 42));
        System.out.printf("NAIVE: %s%n", stats);
        for (long id : accounts) {
            System.out.printf("  account %d: stored=%d ledger=%d%n",
                    id, invariants.storedBalance(id), invariants.ledgerBalance(id));
        }

        // Every transfer wrote its two ledger entries, so invariants 1 and 3 still hold...
        assertThat(invariants.transfersWithBadEntries()).isEmpty();
        assertThat(invariants.ledgerTotal()).isZero();
        // ...but the stored balances no longer match the ledger (invariant 4): updates were lost,
        // and money was created or destroyed as far as the balances are concerned.
        assertThat(invariants.accountsWithDrift()).as("naive mode must show balance drift").isNotEmpty();
        assertThat(sumOfBalances(accounts)).isNotEqualTo(HOT_ACCOUNTS * OPENING_BALANCE);
    }

    @Test
    void optimisticStrategyRetriesConflictsInsteadOfLosingThem() throws Exception {
        List<Long> accounts = openHotAccounts(2);

        RaceStats stats = race(LockingMode.OPTIMISTIC, randomTransfers(accounts, 200, 7));
        System.out.printf("OPTIMISTIC (2 hot accounts): %s%n", stats);

        assertThat(stats.totalAttempts()).as("conflicts happened and were retried")
                .isGreaterThan(stats.succeeded() + stats.insufficientFunds());
        assertThat(stats.unexpected()).isEmpty();
        invariants.assertAllHold();
        assertThat(sumOfBalances(accounts)).isEqualTo(2 * OPENING_BALANCE);
    }

    /**
     * A->B and B->A at the same time is the classic deadlock recipe if each transaction locks
     * "its" source account first. Locking in ascending id order makes it impossible.
     */
    @Test
    void pessimisticOppositeDirectionTransfersNeverDeadlock() throws Exception {
        List<Long> accounts = openHotAccounts(2);
        long a = accounts.get(0);
        long b = accounts.get(1);
        List<TransferCommand> commands = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            commands.add(i % 2 == 0
                    ? new TransferCommand(TransferType.TRANSFER, a, b, 100)
                    : new TransferCommand(TransferType.TRANSFER, b, a, 100));
        }

        RaceStats stats = race(LockingMode.PESSIMISTIC, commands);

        assertThat(stats.succeeded()).as("every transfer succeeds; no deadlock victims").isEqualTo(300);
        assertThat(stats.unexpected()).isEmpty();
        // 150 each way, same amount: both accounts end exactly where they started.
        assertThat(invariants.storedBalance(a)).isEqualTo(OPENING_BALANCE);
        assertThat(invariants.storedBalance(b)).isEqualTo(OPENING_BALANCE);
        invariants.assertAllHold();
    }

    /** Every deposit debits the single funding account: the hottest row in the system. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = LockingMode.class, names = {"PESSIMISTIC", "OPTIMISTIC"})
    void concurrentDepositsAllLandExactlyOnce(LockingMode mode) throws Exception {
        List<Long> accounts = openHotAccounts(4);
        long funding = jdbc.queryForObject("select id from accounts where kind = 'FUNDING'", Long.class);
        long fundingBefore = invariants.storedBalance(funding);
        List<TransferCommand> deposits = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            deposits.add(new TransferCommand(TransferType.DEPOSIT, funding, accounts.get(i % 4), 500));
        }

        RaceStats stats = race(mode, deposits);

        assertThat(stats.unexpected()).isEmpty();
        assertThat(invariants.storedBalance(funding)).isEqualTo(fundingBefore - 500L * stats.succeeded());
        invariants.assertAllHold();
    }

    // ---------------------------------------------------------------------------------------

    private List<Long> openHotAccounts(int count) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(api.newAccountWithBalance(OPENING_BALANCE));
        }
        return ids;
    }

    /** Deterministic (seeded) random transfers between distinct accounts. */
    private static List<TransferCommand> randomTransfers(List<Long> accounts, int count, long seed) {
        Random random = new Random(seed);
        List<TransferCommand> commands = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int from = random.nextInt(accounts.size());
            int to = (from + 1 + random.nextInt(accounts.size() - 1)) % accounts.size();
            long amount = 1 + random.nextLong(MAX_AMOUNT);
            commands.add(new TransferCommand(TransferType.TRANSFER, accounts.get(from), accounts.get(to), amount));
        }
        return commands;
    }

    /**
     * Fires all commands from a pool of {@link #THREADS} threads. A latch releases them at the
     * same instant so they really overlap.
     */
    private RaceStats race(LockingMode mode, List<TransferCommand> commands) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGun = new CountDownLatch(1);
        try {
            List<Future<TransferOutcome>> futures = new ArrayList<>();
            for (TransferCommand command : commands) {
                futures.add(pool.submit(() -> {
                    startGun.await();
                    return transferService.execute(UUID.randomUUID().toString(), command, mode);
                }));
            }
            startGun.countDown();

            int succeeded = 0, insufficient = 0, exhausted = 0, attempts = 0;
            List<Throwable> unexpected = new ArrayList<>();
            for (Future<TransferOutcome> future : futures) {
                try {
                    TransferOutcome outcome = future.get(60, TimeUnit.SECONDS);
                    succeeded++;
                    attempts += outcome.attempts();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof InsufficientFundsException) {
                        insufficient++;
                        attempts++;
                    } else if (cause instanceof ConcurrentUpdateException && mode == LockingMode.OPTIMISTIC) {
                        exhausted++;
                    } else if (mode == LockingMode.NAIVE) {
                        attempts++; // naive may hit deadlocks or constraint errors; that is part of its brokenness
                    } else {
                        unexpected.add(cause);
                    }
                }
            }
            return new RaceStats(commands.size(), succeeded, insufficient, exhausted, attempts, unexpected);
        } finally {
            pool.shutdownNow();
        }
    }

    private long sumOfBalances(List<Long> accounts) {
        return accounts.stream().mapToLong(invariants::storedBalance).sum();
    }

    private long transferRowsOfType(TransferType type) {
        return jdbc.queryForObject("select count(*) from transfers where type = ?", Long.class, type.name());
    }

    /**
     * @param totalAttempts transaction attempts used by the transfers that finished (succeeded or
     *                      were rejected for funds); more attempts than transfers means retries
     */
    record RaceStats(int requested, int succeeded, int insufficientFunds, int retriesExhausted,
                     int totalAttempts, List<Throwable> unexpected) {
    }
}
