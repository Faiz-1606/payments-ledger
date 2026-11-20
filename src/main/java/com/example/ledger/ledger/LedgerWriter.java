package com.example.ledger.ledger;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the two ledger lines for a money movement. This is the only code that creates
 * {@link LedgerEntry} rows, so invariant 1 ("every transfer has exactly two entries that sum
 * to zero") is guaranteed in one place.
 */
@Component
public class LedgerWriter {

    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerWriter(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /**
     * {@code Propagation.MANDATORY}: there must already be a transaction, otherwise Spring
     * throws. Ledger lines written outside the transaction that moves the balances could
     * commit without the balance change (or vice versa), which would break invariant 4.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordDoubleEntry(long transferId, long fromAccountId, long toAccountId,
                                  long amountPaise, Instant at) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("amount must be positive, was " + amountPaise);
        }
        ledgerEntryRepository.save(new LedgerEntry(transferId, fromAccountId, -amountPaise, at));
        ledgerEntryRepository.save(new LedgerEntry(transferId, toAccountId, amountPaise, at));
    }
}
