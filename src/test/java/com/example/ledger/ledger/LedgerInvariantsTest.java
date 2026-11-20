package com.example.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import com.example.ledger.support.AbstractIntegrationTest;

/**
 * Happy-path proof of invariants 1-4 after a realistic mix of deposits, transfers and
 * rejected requests. Phase 3 repeats this under heavy concurrency.
 */
class LedgerInvariantsTest extends AbstractIntegrationTest {

    @Test
    void invariantsHoldAfterMixedSuccessfulAndFailedOperations() throws Exception {
        long alice = api.newAccountWithBalance(50_000);
        long bob = api.newAccountWithBalance(20_000);
        long carol = api.newAccountWithBalance(0);

        api.transfer(alice, bob, 12_345).andExpect(status().isCreated());
        api.transfer(bob, carol, 30_000).andExpect(status().isCreated());
        api.transfer(carol, alice, 1).andExpect(status().isCreated());
        api.transfer(carol, bob, 1_000_000).andExpect(status().isUnprocessableContent()); // insufficient
        api.transfer(alice, alice, 10).andExpect(status().isBadRequest());               // same account
        api.deposit(carol, 5_000).andExpect(status().isCreated());

        // 3 deposits (alice and bob opening + carol later) + 3 transfers = 6 completed
        // transfers, 12 entries. The two rejected requests left nothing behind.
        assertThat(invariants.transferCount()).isEqualTo(6);
        assertThat(invariants.entryCount()).isEqualTo(12);

        // Invariant 1: each transfer has exactly two entries summing to zero.
        assertThat(invariants.transfersWithBadEntries()).isEmpty();
        // Invariant 2: no user wallet is negative.
        assertThat(invariants.negativeNonFundingAccounts()).isEmpty();
        // Invariant 3: the whole ledger sums to zero.
        assertThat(invariants.ledgerTotal()).isZero();
        // Invariant 4: each stored balance equals its ledger sum.
        assertThat(invariants.accountsWithDrift()).isEmpty();

        // And the actual numbers are right.
        assertThat(api.balance(alice)).isEqualTo(50_000 - 12_345 + 1);
        assertThat(api.balance(bob)).isEqualTo(20_000 + 12_345 - 30_000);
        assertThat(api.balance(carol)).isEqualTo(30_000 - 1 + 5_000);
    }

    @Test
    void sumOfUserBalancesEqualsTotalDeposited() throws Exception {
        long alice = api.newAccountWithBalance(7_000);
        long bob = api.newAccountWithBalance(3_000);
        api.transfer(alice, bob, 6_999).andExpect(status().isCreated());
        api.transfer(bob, alice, 9_999).andExpect(status().isCreated());

        long userTotal = jdbc.queryForObject(
                "select sum(balance_paise) from accounts where kind = 'USER'", Long.class);
        long funding = jdbc.queryForObject(
                "select balance_paise from accounts where kind = 'FUNDING'", Long.class);
        assertThat(userTotal).isEqualTo(10_000).isEqualTo(-funding);
        invariants.assertAllHold();
    }
}
