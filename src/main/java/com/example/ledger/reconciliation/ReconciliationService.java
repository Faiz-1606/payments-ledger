package com.example.ledger.reconciliation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.example.ledger.reconciliation.ReconciliationReport.AccountDrift;

/**
 * Re-checks the ledger invariants across <em>all</em> accounts, independently of the code that
 * moves money. If a bug, a manual SQL fix or a broken locking mode (try {@code naive}) ever
 * corrupts a balance, this is what notices.
 *
 * <p>The checks are set-based SQL run through {@link JdbcTemplate}: aggregations over every row
 * are what databases are good at, and loading millions of entities into JPA to sum them in Java
 * would be slow and memory-hungry.
 *
 * <p><b>Why REPEATABLE READ?</b> The report is several queries. Under READ COMMITTED each query
 * would see a different moment, so a transfer committing between "sum all entries" and "compare
 * balances" could show up as false drift. REPEATABLE READ gives the whole transaction one
 * snapshot, and {@code readOnly} tells PostgreSQL it will not write. Transfers keep running
 * meanwhile (MVCC readers never block writers).
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;
    private final AtomicReference<ReconciliationReport> latest = new AtomicReference<>();

    public ReconciliationService(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
        // Gauges sample a value when scraped; they read the latest report.
        Gauge.builder("ledger.reconciliation.drifted.accounts", latest,
                        ref -> ref.get() == null ? 0 : ref.get().driftedAccounts().size())
                .description("Accounts whose stored balance differs from their ledger (last run)")
                .register(registry);
        Gauge.builder("ledger.reconciliation.ledger.total.paise", latest,
                        ref -> ref.get() == null ? 0 : ref.get().ledgerTotalPaise())
                .description("Sum of all ledger entries at the last run; must be 0")
                .register(registry);
        Gauge.builder("ledger.reconciliation.healthy", latest,
                        ref -> ref.get() == null ? Double.NaN : (ref.get().healthy() ? 1 : 0))
                .description("1 if the last reconciliation found no problems, 0 otherwise")
                .register(registry);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport run() {
        Instant startedAt = Instant.now();

        long accounts = count("select count(*) from accounts");
        long transfers = count("select count(*) from transfers");
        long entries = count("select count(*) from ledger_entries");

        // Invariant 3: the whole ledger sums to zero.
        long ledgerTotal = count("select coalesce(sum(amount_paise), 0) from ledger_entries");

        // Invariant 4: each stored balance equals the sum of that account's entries.
        List<AccountDrift> drifted = jdbc.query("""
                select a.id, a.kind, a.balance_paise, coalesce(sum(e.amount_paise), 0) as ledger_balance
                from accounts a
                left join ledger_entries e on e.account_id = a.id
                group by a.id, a.kind, a.balance_paise
                having a.balance_paise <> coalesce(sum(e.amount_paise), 0)
                order by a.id""",
                (rs, rowNum) -> {
                    long stored = rs.getLong("balance_paise");
                    long ledger = rs.getLong("ledger_balance");
                    return new AccountDrift(rs.getLong("id"), rs.getString("kind"), stored, ledger, stored - ledger);
                });
        long totalAbsoluteDrift = drifted.stream().mapToLong(d -> Math.abs(d.driftPaise())).sum();

        // Invariant 1: each transfer has exactly two entries that sum to zero.
        List<Long> unbalanced = jdbc.queryForList("""
                select t.id
                from transfers t
                left join ledger_entries e on e.transfer_id = t.id
                group by t.id
                having count(e.id) <> 2 or coalesce(sum(e.amount_paise), 0) <> 0
                order by t.id""", Long.class);

        // Invariant 2: no non-funding account below zero, by stored or by ledger balance.
        List<Long> negative = jdbc.queryForList("""
                select a.id
                from accounts a
                left join ledger_entries e on e.account_id = a.id
                where a.kind <> 'FUNDING'
                group by a.id, a.balance_paise
                having a.balance_paise < 0 or coalesce(sum(e.amount_paise), 0) < 0
                order by a.id""", Long.class);

        boolean healthy = ledgerTotal == 0 && drifted.isEmpty() && unbalanced.isEmpty() && negative.isEmpty();
        long durationMillis = Duration.between(startedAt, Instant.now()).toMillis();
        ReconciliationReport report = new ReconciliationReport(startedAt, durationMillis, healthy, accounts,
                transfers, entries, ledgerTotal, drifted, totalAbsoluteDrift, unbalanced, negative);

        latest.set(report);
        registry.counter("ledger.reconciliation.runs", "result", healthy ? "healthy" : "drift").increment();
        if (healthy) {
            log.info("Reconciliation OK: {} accounts, {} transfers, {} entries in {} ms",
                    accounts, transfers, entries, durationMillis);
        } else {
            log.warn("Reconciliation found problems: ledgerTotal={} driftedAccounts={} totalDrift={} "
                            + "unbalancedTransfers={} negativeAccounts={}",
                    ledgerTotal, drifted.size(), totalAbsoluteDrift, unbalanced.size(), negative.size());
        }
        return report;
    }

    public Optional<ReconciliationReport> latest() {
        return Optional.ofNullable(latest.get());
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
