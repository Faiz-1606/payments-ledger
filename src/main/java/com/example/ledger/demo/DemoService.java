package com.example.ledger.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.ledger.account.Account;
import com.example.ledger.account.AccountRepository;
import com.example.ledger.account.InsufficientFundsException;
import com.example.ledger.account.SystemAccounts;
import com.example.ledger.common.error.ApiException;
import com.example.ledger.transfer.ConcurrentUpdateException;
import com.example.ledger.transfer.TransferCommand;
import com.example.ledger.transfer.TransferOutcome;
import com.example.ledger.transfer.TransferService;
import com.example.ledger.transfer.TransferType;
import com.example.ledger.transfer.locking.LockingMode;
import com.example.ledger.user.Role;
import com.example.ledger.user.User;
import com.example.ledger.user.UserService;

/**
 * Runs "races": N transfers fired in parallel, server-side, among a handful of DEMO wallets,
 * with a chosen locking strategy, and reports what happened to the money.
 *
 * <p>{@code @Profile("demo")}: this bean (and the controller) exist only when the demo profile
 * is active. Everything here goes through the real {@link TransferService}; the only special
 * thing is that it targets DEMO accounts, which can't exchange money with real wallets.
 */
@Service
@Profile("demo")
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);
    private static final String DEMO_OWNER_EMAIL = "race-demo@ledger.local";

    private final DemoProperties properties;
    private final TransferService transferService;
    private final AccountRepository accountRepository;
    private final UserService userService;
    private final SystemAccounts systemAccounts;
    private final JdbcTemplate jdbc;
    /** One race at a time: the endpoint is public and each race is CPU- and DB-heavy. */
    private final AtomicBoolean raceRunning = new AtomicBoolean(false);

    public DemoService(DemoProperties properties, TransferService transferService,
                       AccountRepository accountRepository, UserService userService,
                       SystemAccounts systemAccounts, JdbcTemplate jdbc) {
        this.properties = properties;
        this.transferService = transferService;
        this.accountRepository = accountRepository;
        this.userService = userService;
        this.systemAccounts = systemAccounts;
        this.jdbc = jdbc;
    }

    /** The current demo wallets: the most recently created set. Creates them on first use. */
    public synchronized List<Long> currentAccountIds() {
        List<Long> ids = jdbc.queryForList(
                "select id from accounts where kind = 'DEMO' order by id desc limit ?", Long.class,
                properties.accounts());
        if (ids.size() < properties.accounts()) {
            return reset();
        }
        return ids.reversed();
    }

    /**
     * A fresh set of demo wallets, each funded by a normal deposit. Old sets stay in the database:
     * the ledger is append-only, so even a corrupted demo run is never "cleaned up" by deleting.
     */
    public synchronized List<Long> reset() {
        long ownerId = demoOwner().getId();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < properties.accounts(); i++) {
            long accountId = accountRepository.save(Account.openDemoWallet(ownerId)).getId();
            transferService.execute(UUID.randomUUID().toString(),
                    new TransferCommand(TransferType.DEPOSIT, systemAccounts.fundingAccountId(), accountId,
                            properties.openingBalancePaise()),
                    LockingMode.PESSIMISTIC);
            ids.add(accountId);
        }
        log.info("Created demo accounts {}", ids);
        return ids;
    }

    public DemoState state() {
        return snapshot(currentAccountIds());
    }

    public RaceResult race(LockingMode mode, int transfers) {
        if (transfers < 1 || transfers > properties.maxTransfers()) {
            throw new DemoException(HttpStatus.BAD_REQUEST, "INVALID_RACE",
                    "transfers must be between 1 and " + properties.maxTransfers());
        }
        if (!raceRunning.compareAndSet(false, true)) {
            throw new DemoException(HttpStatus.CONFLICT, "RACE_IN_PROGRESS",
                    "Another race is running; try again in a few seconds");
        }
        try {
            List<Long> ids = currentAccountIds();
            DemoState before = snapshot(ids);
            long started = System.nanoTime();
            RaceCounts counts = fire(mode, ids, transfers);
            long durationMillis = (System.nanoTime() - started) / 1_000_000;
            DemoState after = snapshot(ids);
            return new RaceResult(mode, transfers, counts.succeeded, counts.insufficientFunds, counts.gaveUp,
                    counts.otherErrors, counts.retries, durationMillis, before, after,
                    after.totalStoredPaise() - before.totalStoredPaise());
        } finally {
            raceRunning.set(false);
        }
    }

    /** N random transfers between distinct demo wallets, all submitted at once to a thread pool. */
    private RaceCounts fire(LockingMode mode, List<Long> ids, int transfers) {
        ExecutorService pool = Executors.newFixedThreadPool(properties.threads());
        try {
            List<Future<TransferOutcome>> futures = new ArrayList<>();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < transfers; i++) {
                int from = random.nextInt(ids.size());
                int to = (from + 1 + random.nextInt(ids.size() - 1)) % ids.size();
                TransferCommand command = new TransferCommand(TransferType.TRANSFER, ids.get(from), ids.get(to),
                        1 + random.nextLong(properties.maxAmountPaise()));
                futures.add(pool.submit(() -> transferService.execute(UUID.randomUUID().toString(), command, mode)));
            }
            RaceCounts counts = new RaceCounts();
            for (Future<TransferOutcome> future : futures) {
                try {
                    TransferOutcome outcome = future.get();
                    counts.succeeded++;
                    counts.retries += outcome.attempts() - 1;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof InsufficientFundsException) {
                        counts.insufficientFunds++;
                    } else if (cause instanceof ConcurrentUpdateException) {
                        counts.gaveUp++;
                    } else {
                        counts.otherErrors++;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Race interrupted", e);
                }
            }
            return counts;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Stored balance vs ledger balance for each demo wallet, plus totals. */
    private DemoState snapshot(List<Long> ids) {
        List<DemoAccount> accounts = new ArrayList<>();
        for (long id : ids) {
            long stored = jdbc.queryForObject("select balance_paise from accounts where id = ?", Long.class, id);
            long ledger = jdbc.queryForObject(
                    "select coalesce(sum(amount_paise), 0) from ledger_entries where account_id = ?", Long.class, id);
            accounts.add(new DemoAccount(id, stored, ledger, stored - ledger));
        }
        long totalStored = accounts.stream().mapToLong(DemoAccount::storedBalancePaise).sum();
        long totalLedger = accounts.stream().mapToLong(DemoAccount::ledgerBalancePaise).sum();
        long ledgerSum = jdbc.queryForObject("select coalesce(sum(amount_paise), 0) from ledger_entries", Long.class);
        return new DemoState(accounts, totalStored, totalLedger, totalStored - totalLedger, ledgerSum);
    }

    private User demoOwner() {
        return userService.findByEmail(DEMO_OWNER_EMAIL)
                .orElseGet(() -> userService.createUser("Race Demo", DEMO_OWNER_EMAIL, null, Role.USER));
    }

    private static final class RaceCounts {
        int succeeded;
        int insufficientFunds;
        int gaveUp;
        int otherErrors;
        int retries;
    }

    public record DemoAccount(long id, long storedBalancePaise, long ledgerBalancePaise, long driftPaise) {
    }

    /**
     * @param ledgerSumPaise sum of ALL ledger entries in the database (invariant 3); stays 0 even
     *                       in naive mode, because every transfer still writes both entries
     */
    public record DemoState(List<DemoAccount> accounts, long totalStoredPaise, long totalLedgerPaise,
                            long totalDriftPaise, long ledgerSumPaise) {
    }

    /**
     * @param moneyCreatedPaise change in the total stored balance of the demo wallets. Transfers
     *                          only move money between them, so anything other than 0 means money
     *                          was created or destroyed.
     */
    public record RaceResult(LockingMode mode, int requested, int succeeded, int insufficientFunds, int gaveUp,
                             int otherErrors, int optimisticRetries, long durationMillis,
                             DemoState before, DemoState after, long moneyCreatedPaise) {
    }

    static final class DemoException extends ApiException {
        DemoException(HttpStatus status, String code, String detail) {
            super(status, code, "Race demo", detail);
        }
    }
}
