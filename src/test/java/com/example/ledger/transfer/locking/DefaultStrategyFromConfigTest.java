package com.example.ledger.transfer.locking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.example.ledger.support.AbstractIntegrationTest;

/**
 * Boots the real application with {@code ledger.locking=optimistic} and checks the API uses it.
 * (A different property means a second cached Spring context, sharing the same database.)
 */
@TestPropertySource(properties = "ledger.locking=optimistic")
class DefaultStrategyFromConfigTest extends AbstractIntegrationTest {

    @Autowired
    LockingStrategies lockingStrategies;

    @Test
    void configuredStrategyIsTheDefault() throws Exception {
        assertThat(lockingStrategies.defaultMode()).isEqualTo(LockingMode.OPTIMISTIC);
        assertThat(lockingStrategies.get(LockingMode.NAIVE)).isInstanceOf(NaiveLockingStrategy.class);
        assertThat(lockingStrategies.get(LockingMode.PESSIMISTIC)).isInstanceOf(PessimisticLockingStrategy.class);

        long alice = api.newAccountWithBalance(1_000);
        long bob = api.newAccountWithBalance(0);
        api.transfer(alice, bob, 400).andExpect(status().isCreated());
        assertThat(api.balance(bob)).isEqualTo(400);
        invariants.assertAllHold();
    }
}
