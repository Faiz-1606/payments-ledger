package com.example.ledger.transfer.locking;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

/**
 * "Full jitter" exponential backoff between optimistic-lock retries:
 * {@code sleep = random(0, min(maxBackoff, baseBackoff * 2^(attempt-1)))}.
 *
 * <p>Why jitter? If 20 transactions collide and all retry after exactly 10 ms, they collide
 * again at 10 ms, then at 20 ms, and so on (a "thundering herd"). Random delays spread the
 * retries out so most of them find the row free.
 */
@Component
public class RetryBackoff {

    private final long baseMillis;
    private final long maxMillis;

    public RetryBackoff(LockingProperties properties) {
        this.baseMillis = Math.max(1, properties.optimistic().baseBackoff().toMillis());
        this.maxMillis = Math.max(baseMillis, properties.optimistic().maxBackoff().toMillis());
    }

    /** Upper bound of the delay after the given failed attempt (1-based). */
    public long capMillis(int failedAttempt) {
        int exponent = Math.min(failedAttempt - 1, 20); // 2^20 is already huge; avoid overflow
        return Math.min(maxMillis, baseMillis << exponent);
    }

    public Duration delayAfter(int failedAttempt) {
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(capMillis(failedAttempt) + 1));
    }

    public void pauseAfter(int failedAttempt) {
        try {
            Thread.sleep(delayAfter(failedAttempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // keep the interrupt visible to callers
            throw new IllegalStateException("Interrupted while backing off", e);
        }
    }
}
