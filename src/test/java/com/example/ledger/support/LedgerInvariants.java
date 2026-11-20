package com.example.ledger.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Independent checker for the five ledger invariants, written as plain SQL.
 *
 * <p>It deliberately shares no code with the application: a test oracle that reuses
 * production code would inherit the same bugs.
 */
public final class LedgerInvariants {

    private final JdbcTemplate jdbc;

    public LedgerInvariants(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Invariant 1: every completed transfer has exactly two entries: -amount on the source and
     * +amount on the destination. Returns ids of transfers that break it.
     */
    public List<Long> transfersWithBadEntries() {
        return jdbc.queryForList("""
                select t.id
                from transfers t
                left join ledger_entries e on e.transfer_id = t.id
                where t.status = 'COMPLETED'
                group by t.id, t.amount_paise, t.from_account_id, t.to_account_id
                having count(e.id) <> 2
                    or coalesce(sum(e.amount_paise), 0) <> 0
                    or count(*) filter (where e.account_id = t.from_account_id
                                         and e.amount_paise = -t.amount_paise) <> 1
                    or count(*) filter (where e.account_id = t.to_account_id
                                         and e.amount_paise = t.amount_paise) <> 1
                """, Long.class);
    }

    /** Ledger entries that do not belong to any transfer (should never exist). */
    public long orphanEntries() {
        return jdbc.queryForObject("""
                select count(*) from ledger_entries e
                where not exists (select 1 from transfers t where t.id = e.transfer_id)""", Long.class);
    }

    /**
     * Invariant 2: no non-funding account is negative. Checked both on the stored balance and
     * on the balance implied by the ledger (a lost update can hide an overdraft in the stored
     * balance while the ledger shows it).
     */
    public List<Long> negativeNonFundingAccounts() {
        return jdbc.queryForList("""
                select a.id
                from accounts a
                left join ledger_entries e on e.account_id = a.id
                where a.kind <> 'FUNDING'
                group by a.id, a.balance_paise
                having a.balance_paise < 0 or coalesce(sum(e.amount_paise), 0) < 0
                """, Long.class);
    }

    /** Invariant 3: all ledger entries sum to zero. Returns the sum (0 when healthy). */
    public long ledgerTotal() {
        return jdbc.queryForObject("select coalesce(sum(amount_paise), 0) from ledger_entries", Long.class);
    }

    /** Invariant 4: stored balance equals the sum of the account's entries. Returns drifted account ids. */
    public List<Long> accountsWithDrift() {
        return jdbc.queryForList("""
                select a.id
                from accounts a
                left join ledger_entries e on e.account_id = a.id
                group by a.id, a.balance_paise
                having a.balance_paise <> coalesce(sum(e.amount_paise), 0)
                """, Long.class);
    }

    /** Invariant 5 at the storage level: no idempotency key was used for two transfers. */
    public long duplicateIdempotencyKeys() {
        return jdbc.queryForObject("""
                select count(*) from (
                    select idempotency_key from transfers
                    where idempotency_key is not null
                    group by idempotency_key having count(*) > 1) d""", Long.class);
    }

    public long storedBalance(long accountId) {
        return jdbc.queryForObject("select balance_paise from accounts where id = ?", Long.class, accountId);
    }

    public long ledgerBalance(long accountId) {
        return jdbc.queryForObject(
                "select coalesce(sum(amount_paise), 0) from ledger_entries where account_id = ?",
                Long.class, accountId);
    }

    public long transferCount() {
        return jdbc.queryForObject("select count(*) from transfers", Long.class);
    }

    public long entryCount() {
        return jdbc.queryForObject("select count(*) from ledger_entries", Long.class);
    }

    /** True if at least one of invariants 1-4 is broken. */
    public boolean anyViolated() {
        return !transfersWithBadEntries().isEmpty()
                || orphanEntries() != 0
                || !negativeNonFundingAccounts().isEmpty()
                || ledgerTotal() != 0
                || !accountsWithDrift().isEmpty();
    }

    /** Asserts invariants 1-5 all hold, with a readable message for whichever fails. */
    public void assertAllHold() {
        assertThat(transfersWithBadEntries()).as("invariant 1: transfers without exactly two balanced entries").isEmpty();
        assertThat(orphanEntries()).as("invariant 1: ledger entries without a transfer").isZero();
        assertThat(negativeNonFundingAccounts()).as("invariant 2: negative non-funding accounts").isEmpty();
        assertThat(ledgerTotal()).as("invariant 3: sum of all ledger entries").isZero();
        assertThat(accountsWithDrift()).as("invariant 4: accounts whose balance != sum of entries").isEmpty();
        assertThat(duplicateIdempotencyKeys()).as("invariant 5: idempotency keys used twice").isZero();
    }
}
