package com.example.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import com.example.ledger.support.AbstractIntegrationTest;

class DepositApiTest extends AbstractIntegrationTest {

    @Test
    void depositMovesMoneyFromFundingAccount() throws Exception {
        long funding = jdbc.queryForObject("select id from accounts where kind = 'FUNDING'", Long.class);
        long alice = api.newAccountWithBalance(0);

        api.deposit(alice, 25_000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.fromAccountId").value(funding))
                .andExpect(jsonPath("$.toAccountId").value(alice));

        assertThat(api.balance(alice)).isEqualTo(25_000);
        assertThat(invariants.storedBalance(funding))
                .as("the funding account is the only one allowed to go negative")
                .isEqualTo(-25_000);
        invariants.assertAllHold();
    }

    @Test
    void depositToUnknownAccountIsNotFound() throws Exception {
        api.deposit(123_456, 100)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        assertThat(invariants.transferCount()).isZero();
    }

    @Test
    void zeroOrNegativeDepositIsValidationError() throws Exception {
        long alice = api.newAccountWithBalance(0);
        api.deposit(alice, 0).andExpect(status().isBadRequest());
        api.deposit(alice, -1).andExpect(status().isBadRequest());
        assertThat(api.balance(alice)).isZero();
    }

    @Test
    void cannotDepositIntoTheFundingAccountItself() throws Exception {
        long funding = jdbc.queryForObject("select id from accounts where kind = 'FUNDING'", Long.class);
        api.deposit(funding, 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT_TRANSFER"));
    }
}
