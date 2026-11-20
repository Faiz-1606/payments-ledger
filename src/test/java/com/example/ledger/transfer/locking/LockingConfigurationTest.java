package com.example.ledger.transfer.locking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binding of {@code ledger.locking} and {@code ledger.optimistic.*}, tested with
 * {@link ApplicationContextRunner}: a tiny Spring context in milliseconds, no database.
 */
class LockingConfigurationTest {

    @Configuration
    @EnableConfigurationProperties(LockingProperties.class)
    static class PropertiesOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    void defaultsArePessimisticWithBoundedRetries() {
        runner.run(context -> {
            LockingProperties properties = context.getBean(LockingProperties.class);
            assertThat(properties.locking()).isEqualTo(LockingMode.PESSIMISTIC);
            assertThat(properties.optimistic().maxAttempts()).isEqualTo(10);
            assertThat(properties.optimistic().baseBackoff()).isEqualTo(Duration.ofMillis(5));
        });
    }

    @Test
    void lowercaseConfigValueSelectsTheStrategy() {
        runner.withPropertyValues("ledger.locking=optimistic", "ledger.optimistic.max-attempts=3")
                .run(context -> {
                    LockingProperties properties = context.getBean(LockingProperties.class);
                    assertThat(properties.locking()).isEqualTo(LockingMode.OPTIMISTIC);
                    assertThat(properties.optimistic().maxAttempts()).isEqualTo(3);
                });
    }

    @Test
    void typoInLockingModeFailsAtStartup() {
        runner.withPropertyValues("ledger.locking=pesimistic")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void zeroAttemptsIsRejected() {
        runner.withPropertyValues("ledger.optimistic.max-attempts=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
