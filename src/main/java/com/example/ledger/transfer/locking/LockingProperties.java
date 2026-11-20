package com.example.ledger.transfer.locking;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Type-safe binding of the {@code ledger.*} locking settings in application.yml:
 * <pre>
 * ledger:
 *   locking: pessimistic          # naive | pessimistic | optimistic
 *   optimistic:
 *     max-attempts: 10
 *     base-backoff: 5ms
 *     max-backoff: 100ms
 * </pre>
 * A record with {@code @ConfigurationProperties} is immutable and validated at startup: a typo
 * such as {@code ledger.locking=pesimistic} fails fast instead of silently using a default.
 */
@ConfigurationProperties(prefix = "ledger")
public record LockingProperties(
        @DefaultValue("pessimistic") LockingMode locking,
        @DefaultValue Optimistic optimistic) {

    /**
     * @param maxAttempts total tries of the whole transaction (1 = no retry)
     * @param baseBackoff backoff cap for the first retry; doubles each retry
     * @param maxBackoff  backoff cap never exceeds this
     */
    public record Optimistic(
            @DefaultValue("10") int maxAttempts,
            @DefaultValue("5ms") Duration baseBackoff,
            @DefaultValue("100ms") Duration maxBackoff) {

        public Optimistic {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("ledger.optimistic.max-attempts must be >= 1");
            }
        }
    }
}
