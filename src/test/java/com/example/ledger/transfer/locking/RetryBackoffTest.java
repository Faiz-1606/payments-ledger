package com.example.ledger.transfer.locking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RetryBackoffTest {

    private final RetryBackoff backoff = new RetryBackoff(new LockingProperties(LockingMode.OPTIMISTIC,
            new LockingProperties.Optimistic(10, Duration.ofMillis(5), Duration.ofMillis(100))));

    @Test
    void capDoublesPerAttemptUntilTheMaximum() {
        assertThat(backoff.capMillis(1)).isEqualTo(5);
        assertThat(backoff.capMillis(2)).isEqualTo(10);
        assertThat(backoff.capMillis(3)).isEqualTo(20);
        assertThat(backoff.capMillis(5)).isEqualTo(80);
        assertThat(backoff.capMillis(6)).isEqualTo(100);
        assertThat(backoff.capMillis(1_000)).isEqualTo(100);
    }

    @Test
    void delayIsRandomButNeverAboveTheCap() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(backoff.delayAfter(3).toMillis()).isBetween(0L, 20L);
        }
    }
}
