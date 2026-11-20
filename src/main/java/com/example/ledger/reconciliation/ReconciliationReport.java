package com.example.ledger.reconciliation;

import java.time.Instant;
import java.util.List;

/**
 * Result of one reconciliation run. {@code healthy} is true only if every check passed.
 *
 * @param ledgerTotalPaise      invariant 3: sum of all ledger entries (must be 0)
 * @param driftedAccounts       invariant 4: accounts whose stored balance != sum of their entries
 * @param unbalancedTransferIds invariant 1: transfers without exactly two entries summing to 0
 * @param negativeAccountIds    invariant 2: non-funding accounts with a negative stored or ledger balance
 */
public record ReconciliationReport(
        Instant startedAt,
        long durationMillis,
        boolean healthy,
        long accountsChecked,
        long transfersChecked,
        long ledgerEntriesChecked,
        long ledgerTotalPaise,
        List<AccountDrift> driftedAccounts,
        long totalAbsoluteDriftPaise,
        List<Long> unbalancedTransferIds,
        List<Long> negativeAccountIds) {

    /** One account whose cached balance disagrees with its ledger. {@code driftPaise = stored - ledger}. */
    public record AccountDrift(long accountId, String kind, long storedBalancePaise, long ledgerBalancePaise,
                               long driftPaise) {
    }
}
