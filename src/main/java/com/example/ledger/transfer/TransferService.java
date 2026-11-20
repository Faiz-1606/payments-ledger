package com.example.ledger.transfer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.ledger.account.Account;
import com.example.ledger.account.AccountKind;
import com.example.ledger.account.AccountRepository;
import com.example.ledger.account.SystemAccounts;
import com.example.ledger.common.error.ForbiddenException;
import com.example.ledger.common.error.NotFoundException;
import com.example.ledger.idempotency.IdempotencyService;
import com.example.ledger.idempotency.IdempotencyService.IdempotentResult;
import com.example.ledger.idempotency.RequestHasher;
import com.example.ledger.ledger.LedgerWriter;
import com.example.ledger.outbox.OutboxWriter;
import com.example.ledger.security.AccessPolicy;
import com.example.ledger.security.CurrentUser;
import com.example.ledger.transfer.locking.AccountPair;
import com.example.ledger.transfer.locking.LockingMode;
import com.example.ledger.transfer.locking.LockingStrategies;
import com.example.ledger.transfer.locking.LockingStrategy;
import com.example.ledger.transfer.locking.RetryBackoff;

/**
 * Moves money. One attempt = one database transaction that, atomically:
 * <ol>
 *   <li>claims the Idempotency-Key (or replays the stored response),</li>
 *   <li>loads both accounts the way the chosen {@link LockingStrategy} says,</li>
 *   <li>debits one and credits the other,</li>
 *   <li>inserts the transfer row and the two ledger entries,</li>
 *   <li>inserts a TransferCompleted event into the outbox (published to Kafka later),</li>
 *   <li>stores the response under the Idempotency-Key.</li>
 * </ol>
 * Either all of it commits or none of it does. With optimistic locking a conflicting attempt
 * rolls back and the whole transaction is retried.
 *
 * <p><b>Why a {@link TransactionTemplate} instead of {@code @Transactional} here?</b> A retry
 * must start a <em>new</em> transaction, because the failed one is already rolled back. With
 * {@code @Transactional} the retry loop would have to live in a different bean (calling a
 * {@code @Transactional} method of this same class bypasses the proxy). The template makes the
 * boundary visible: everything inside {@code execute(...)} is one transaction, and the loop
 * around it is not.
 *
 * <p><b>Isolation: READ COMMITTED</b> (PostgreSQL's default, set explicitly so it is visible).
 * Each statement sees data committed before it started. That alone does not prevent lost
 * updates, so the strategies add protection: row locks (pessimistic) or a version check
 * (optimistic). The naive strategy has neither, which is why it loses updates. See
 * docs/LEARNING.md, Phase 3, for why not REPEATABLE READ or SERIALIZABLE.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;
    private final AccessPolicy accessPolicy;
    private final LedgerWriter ledgerWriter;
    private final OutboxWriter outboxWriter;
    private final SystemAccounts systemAccounts;
    private final IdempotencyService idempotencyService;
    private final LockingStrategies lockingStrategies;
    private final RetryBackoff retryBackoff;
    private final TransferMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    public TransferService(TransferRepository transferRepository, AccountRepository accountRepository,
                           AccessPolicy accessPolicy, LedgerWriter ledgerWriter, OutboxWriter outboxWriter,
                           SystemAccounts systemAccounts, IdempotencyService idempotencyService,
                           LockingStrategies lockingStrategies, RetryBackoff retryBackoff,
                           TransferMetrics metrics, PlatformTransactionManager transactionManager) {
        this.transferRepository = transferRepository;
        this.accountRepository = accountRepository;
        this.accessPolicy = accessPolicy;
        this.ledgerWriter = ledgerWriter;
        this.outboxWriter = outboxWriter;
        this.systemAccounts = systemAccounts;
        this.idempotencyService = idempotencyService;
        this.lockingStrategies = lockingStrategies;
        this.retryBackoff = retryBackoff;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * A user-initiated transfer. The caller must own the source account. Ownership never
     * changes after an account is opened, so checking it before the transaction is safe.
     */
    public TransferOutcome transfer(CurrentUser caller, String idempotencyKey,
                                    long fromAccountId, long toAccountId, long amountPaise) {
        Account source = accountRepository.findById(fromAccountId)
                .orElseThrow(() -> NotFoundException.account(fromAccountId));
        accessPolicy.requireCanDebit(caller, source);
        return execute(caller.userId(), idempotencyKey,
                new TransferCommand(TransferType.TRANSFER, fromAccountId, toAccountId, amountPaise),
                lockingStrategies.defaultMode());
    }

    /** A deposit is a transfer out of the system funding account. ADMIN only (SecurityConfig). */
    public TransferOutcome deposit(CurrentUser caller, String idempotencyKey, long toAccountId, long amountPaise) {
        return execute(caller.userId(), idempotencyKey,
                new TransferCommand(TransferType.DEPOSIT, systemAccounts.fundingAccountId(), toAccountId, amountPaise),
                lockingStrategies.defaultMode());
    }

    /**
     * Runs a money movement with an explicit locking strategy and no end-user caller: used by
     * trusted internal code (the race demo) and tests. No ownership check happens here.
     */
    public TransferOutcome execute(String idempotencyKey, TransferCommand command, LockingMode mode) {
        return execute(null, idempotencyKey, command, mode);
    }

    /** Records metrics around {@link #executeWithRetries}: one count and one latency per request. */
    private TransferOutcome execute(Long initiatedBy, String idempotencyKey, TransferCommand command,
                                    LockingMode mode) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // Retrying inside someone else's transaction cannot work: after the first failure
            // that outer transaction is already marked rollback-only.
            throw new IllegalStateException("TransferService must not be called inside an existing transaction");
        }
        Timer.Sample timer = metrics.startTimer();
        try {
            TransferOutcome outcome = executeWithRetries(initiatedBy, idempotencyKey, command, mode);
            metrics.finished(timer, command.type(), mode, outcome.replayed() ? "replayed" : "completed");
            return outcome;
        } catch (RuntimeException e) {
            metrics.finished(timer, command.type(), mode, TransferMetrics.outcomeOf(e));
            throw e;
        }
    }

    private TransferOutcome executeWithRetries(Long initiatedBy, String idempotencyKey, TransferCommand command,
                                               LockingMode mode) {
        if (command.fromAccountId() == command.toAccountId()) {
            throw InvalidTransferException.sameAccount(command.fromAccountId());
        }
        LockingStrategy strategy = lockingStrategies.get(mode);
        // The caller is part of the fingerprint: another user reusing the same key gets a 422,
        // never this user's stored response.
        String requestHash = RequestHasher.sha256Hex(canonicalForm(command) + "|by="
                + (initiatedBy == null ? "system" : initiatedBy));

        for (int attempt = 1; ; attempt++) {
            final int thisAttempt = attempt;
            try {
                // Everything inside this lambda is ONE database transaction.
                return transactionTemplate.execute(status -> {
                    IdempotentResult<TransferResponse> result = idempotencyService.execute(
                            idempotencyKey, requestHash, TransferResponse.class, TransferResponse::id,
                            () -> moveMoney(command, idempotencyKey, strategy));
                    return new TransferOutcome(result.response(), result.replayed(), thisAttempt);
                });
            } catch (OptimisticLockingFailureException conflict) {
                // The transaction has already been rolled back by the template.
                if (attempt >= strategy.maxAttempts()) {
                    throw new ConcurrentUpdateException("Gave up after " + attempt
                            + " attempts because of concurrent updates to account "
                            + command.fromAccountId() + " or " + command.toAccountId());
                }
                log.debug("Optimistic lock conflict on attempt {} for {}, retrying", attempt, command);
                metrics.lockRetry(mode);
                retryBackoff.pauseAfter(attempt);
            }
        }
    }

    /** Field order is fixed, so equal requests always produce an equal string (and hash). */
    static String canonicalForm(TransferCommand command) {
        return command.type() + "|from=" + command.fromAccountId() + "|to=" + command.toAccountId()
                + "|amount=" + command.amountPaise();
    }

    /** The money movement itself. Runs inside the transaction opened by {@link #execute}. */
    private TransferResponse moveMoney(TransferCommand command, String idempotencyKey, LockingStrategy strategy) {
        AccountPair accounts = strategy.loadAccounts(command.fromAccountId(), command.toAccountId());
        checkEligibility(command, accounts.from(), accounts.to());

        strategy.moveMoney(accounts, command.amountPaise());

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Transfer transfer = transferRepository.save(new Transfer(idempotencyKey, command.type(),
                command.fromAccountId(), command.toAccountId(), command.amountPaise(), now));
        ledgerWriter.recordDoubleEntry(transfer.getId(), command.fromAccountId(), command.toAccountId(),
                command.amountPaise(), now);
        // Same transaction: the event exists if and only if the transfer committed.
        outboxWriter.transferCompleted(transfer.getId(), command.type().name(), command.fromAccountId(),
                command.toAccountId(), command.amountPaise(), now);
        log.debug("Transfer {} {}: {} -> {} ({} paise)", transfer.getId(), command.type(),
                command.fromAccountId(), command.toAccountId(), command.amountPaise());
        return TransferResponse.from(transfer);
    }

    /**
     * With {@code accountId}: transfers touching that account (caller must be allowed to view it).
     * Without: everything for an ADMIN, otherwise transfers touching any of the caller's accounts.
     */
    @Transactional(readOnly = true)
    public Page<Transfer> listTransfers(CurrentUser caller, Long accountId, Pageable pageable) {
        if (accountId != null) {
            Account account = accountRepository.findById(accountId)
                    .orElseThrow(() -> NotFoundException.account(accountId));
            accessPolicy.requireCanView(caller, account);
            return transferRepository.findInvolvingAccount(accountId, pageable);
        }
        if (caller.admin()) {
            return transferRepository.findAllByOrderByIdDesc(pageable);
        }
        List<Long> ownAccountIds = accountRepository.findByOwnerIdOrderByIdAsc(caller.userId()).stream()
                .map(Account::getId)
                .toList();
        if (ownAccountIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return transferRepository.findInvolvingAnyAccount(ownAccountIds, pageable);
    }

    /** Visible to an ADMIN, or to the owner of either side of the transfer. */
    @Transactional(readOnly = true)
    public Transfer getTransfer(CurrentUser caller, long transferId) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> NotFoundException.transfer(transferId));
        if (caller.admin()) {
            return transfer;
        }
        boolean involved = accountRepository.findAllById(List.of(transfer.getFromAccountId(), transfer.getToAccountId()))
                .stream()
                .anyMatch(account -> accessPolicy.isOwner(caller, account));
        if (!involved) {
            throw new ForbiddenException("Transfer " + transferId + " does not involve your accounts");
        }
        return transfer;
    }

    private static void checkEligibility(TransferCommand command, Account from, Account to) {
        if (to.getKind() != AccountKind.USER) {
            throw InvalidTransferException.notEligible(to.getId(), "is a system account and cannot receive money");
        }
        if (command.type() == TransferType.TRANSFER && from.getKind() != AccountKind.USER) {
            throw InvalidTransferException.notEligible(from.getId(), "is a system account and cannot send transfers");
        }
        if (!from.getCurrency().equals(to.getCurrency())) {
            throw InvalidTransferException.currencyMismatch(from.getId(), to.getId());
        }
    }
}
