package com.example.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import com.example.ledger.support.AbstractIntegrationTest;

/**
 * The database is the last line of defence. These tests bypass the application entirely
 * (raw SQL) to prove the constraints in V1 reject corrupt data on their own.
 */
class SchemaConstraintsTest extends AbstractIntegrationTest {

    @Test
    void ledgerEntriesCannotBeUpdated() throws Exception {
        seedOneTransfer();
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount_paise = amount_paise * 2"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void ledgerEntriesCannotBeDeleted() throws Exception {
        seedOneTransfer();
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void userWalletBalanceCannotBeNegativeInTheDatabase() throws Exception {
        long alice = api.newAccountWithBalance(100);
        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET balance_paise = -1 WHERE id = ?", alice))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_accounts_non_negative");
    }

    @Test
    void transferBetweenSameAccountIsRejectedByTheDatabase() throws Exception {
        long alice = api.newAccountWithBalance(100);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO transfers (type, from_account_id, to_account_id, amount_paise, status)
                VALUES ('TRANSFER', ?, ?, 10, 'COMPLETED')""", alice, alice))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_transfers_distinct_accounts");
    }

    @Test
    void secondFundingAccountIsRejected() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO accounts (kind) VALUES ('FUNDING')"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_accounts_single_funding");
    }

    @Test
    void sameIdempotencyKeyCannotBeUsedForTwoTransfers() throws Exception {
        long alice = api.newAccountWithBalance(100);
        long bob = api.newAccountWithBalance(0);
        String insert = """
                INSERT INTO transfers (idempotency_key, type, from_account_id, to_account_id, amount_paise, status)
                VALUES ('key-1', 'TRANSFER', ?, ?, 10, 'COMPLETED')""";
        jdbc.update(insert, alice, bob);
        assertThatThrownBy(() -> jdbc.update(insert, alice, bob))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_transfers_idempotency_key");
    }

    private void seedOneTransfer() throws Exception {
        long alice = api.newAccountWithBalance(1_000);
        long bob = api.newAccountWithBalance(0);
        api.transfer(alice, bob, 10).andExpect(status().isCreated());
    }
}
