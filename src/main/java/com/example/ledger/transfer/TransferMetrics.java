package com.example.ledger.transfer;

import java.util.Locale;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;

import com.example.ledger.account.InsufficientFundsException;
import com.example.ledger.common.error.ApiException;
import com.example.ledger.transfer.locking.LockingMode;

/**
 * Custom Micrometer metrics for money movement. Exposed at /actuator/metrics and, in Prometheus
 * format, at /actuator/prometheus (dots become underscores, counters get a {@code _total} suffix):
 * <ul>
 *   <li>{@code ledger.transfers} counter, tagged type / strategy / outcome</li>
 *   <li>{@code ledger.transfer.duration} timer (histogram, so p50/p95/p99 can be computed),
 *       same tags</li>
 *   <li>{@code ledger.lock.retries} counter: optimistic-lock conflicts that caused a retry</li>
 *   <li>{@code ledger.idempotency.replays} counter: requests answered from the idempotency store</li>
 * </ul>
 * Tag values must come from a small fixed set (never ids or amounts), otherwise every distinct
 * value creates a new time series ("cardinality explosion").
 */
@Component
public class TransferMetrics {

    private final MeterRegistry registry;

    public TransferMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void finished(Timer.Sample sample, TransferType type, LockingMode mode, String outcome) {
        Tags tags = Tags.of("type", type.name(), "strategy", lower(mode.name()), "outcome", outcome);
        registry.counter("ledger.transfers", tags).increment();
        sample.stop(Timer.builder("ledger.transfer.duration")
                .description("Time to execute a transfer or deposit, including retries")
                .tags(tags)
                .publishPercentileHistogram()
                .register(registry));
        if ("replayed".equals(outcome)) {
            registry.counter("ledger.idempotency.replays", "type", type.name()).increment();
        }
    }

    public void lockRetry(LockingMode mode) {
        registry.counter("ledger.lock.retries", "strategy", lower(mode.name())).increment();
    }

    /** Maps an exception to a low-cardinality outcome tag. */
    public static String outcomeOf(RuntimeException e) {
        if (e instanceof InsufficientFundsException) {
            return "insufficient_funds";
        }
        if (e instanceof ConcurrentUpdateException || e instanceof ConcurrencyFailureException) {
            return "conflict";
        }
        if (e instanceof ApiException) {
            return "rejected";
        }
        return "error";
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
