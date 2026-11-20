package com.example.ledger.transfer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ledger.account.Account;
import com.example.ledger.account.AccountKind;
import com.example.ledger.account.AccountRepository;
import com.example.ledger.account.SystemAccounts;
import com.example.ledger.common.error.NotFoundException;
import com.example.ledger.idempotency.IdempotencyService;
import com.example.ledger.idempotency.IdempotencyService.IdempotentResult;
import com.example.ledger.idempotency.RequestHasher;
import com.example.ledger.ledger.LedgerWriter;

/**
 * Moves money. One call = one database transaction that, atomically:
 * <ol>
 *   <li>claims the Idempotency-Key (or replays the stored response),</li>
 *   <li>locks both accounts,</li>
 *   <li>debits one and credits the other,</li>
 *   <li>inserts the transfer row,</li>
 *   <li>inserts the two ledger entries,</li>
 *   <li>stores the response under the Idempotency-Key.</li>
 * </ol>
 * Either all four happen or none do.
 */
@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final LedgerWriter ledgerWriter;
    private final SystemAccounts systemAccounts;
    private final IdempotencyService idempotencyService;

    public TransferService(AccountRepository accountRepository, TransferRepository transferRepository,
                           LedgerWriter ledgerWriter, SystemAccounts systemAccounts,
                           IdempotencyService idempotencyService) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.ledgerWriter = ledgerWriter;
        this.systemAccounts = systemAccounts;
        this.idempotencyService = idempotencyService;
    }

    /**
     * {@code @Transactional} works through a proxy: Spring wraps this bean, opens a transaction
     * before the method runs, commits if it returns normally and rolls back if it throws a
     * RuntimeException. (Calling it from another method of this same class would bypass the
     * proxy and therefore the transaction.)
     */
    @Transactional
    public TransferOutcome transfer(String idempotencyKey, long fromAccountId, long toAccountId, long amountPaise) {
        return executeOnce(idempotencyKey,
                new TransferCommand(TransferType.TRANSFER, fromAccountId, toAccountId, amountPaise));
    }

    /** A deposit is a transfer out of the system funding account. */
    @Transactional
    public TransferOutcome deposit(String idempotencyKey, long toAccountId, long amountPaise) {
        return executeOnce(idempotencyKey, new TransferCommand(TransferType.DEPOSIT,
                systemAccounts.fundingAccountId(), toAccountId, amountPaise));
    }

    @Transactional(readOnly = true)
    public Page<Transfer> listTransfers(Long accountId, Pageable pageable) {
        if (accountId == null) {
            return transferRepository.findAllByOrderByIdDesc(pageable);
        }
        return transferRepository.findInvolvingAccount(accountId, pageable);
    }

    @Transactional(readOnly = true)
    public Transfer getTransfer(long transferId) {
        return transferRepository.findById(transferId).orElseThrow(() -> NotFoundException.transfer(transferId));
    }

    /** Wraps {@link #execute} so it runs at most once per Idempotency-Key. */
    private TransferOutcome executeOnce(String idempotencyKey, TransferCommand command) {
        String requestHash = RequestHasher.sha256Hex(canonicalForm(command));
        IdempotentResult<TransferResponse> result = idempotencyService.execute(
                idempotencyKey, requestHash, TransferResponse.class, TransferResponse::id,
                () -> execute(command, idempotencyKey));
        return new TransferOutcome(result.response(), result.replayed());
    }

    /** Field order is fixed, so equal requests always produce an equal string (and hash). */
    static String canonicalForm(TransferCommand command) {
        return command.type() + "|from=" + command.fromAccountId() + "|to=" + command.toAccountId()
                + "|amount=" + command.amountPaise();
    }

    private TransferResponse execute(TransferCommand command, String idempotencyKey) {
        if (command.fromAccountId() == command.toAccountId()) {
            throw InvalidTransferException.sameAccount(command.fromAccountId());
        }

        // Lock both rows with SELECT ... FOR UPDATE, always lowest id first. Phase 3 explains
        // why the order matters (deadlocks) and compares this with other strategies.
        long firstId = Math.min(command.fromAccountId(), command.toAccountId());
        long secondId = Math.max(command.fromAccountId(), command.toAccountId());
        Account first = lockAccount(firstId);
        Account second = lockAccount(secondId);
        Account from = first.getId() == command.fromAccountId() ? first : second;
        Account to = from == first ? second : first;

        checkEligibility(command, from, to);

        // Entity methods enforce "no negative balance". These are managed entities, so the new
        // balances are written by Hibernate's dirty checking when the transaction commits.
        from.debit(command.amountPaise());
        to.credit(command.amountPaise());

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Transfer transfer = transferRepository.save(new Transfer(idempotencyKey, command.type(),
                from.getId(), to.getId(), command.amountPaise(), now));
        ledgerWriter.recordDoubleEntry(transfer.getId(), from.getId(), to.getId(), command.amountPaise(), now);
        return TransferResponse.from(transfer);
    }

    private Account lockAccount(long accountId) {
        return accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> NotFoundException.account(accountId));
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
