package com.example.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.example.ledger.support.AbstractIntegrationTest;
import com.example.ledger.support.TestApi;

class TransferApiTest extends AbstractIntegrationTest {

    @Test
    void transferMovesMoneyAndWritesTwoBalancedEntries() throws Exception {
        long alice = api.newAccountWithBalance(10_000);
        long bob = api.newAccountWithBalance(1_000);

        MvcResult result = api.transfer(alice, bob, 3_000)
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.fromAccountId").value(alice))
                .andExpect(jsonPath("$.toAccountId").value(bob))
                .andExpect(jsonPath("$.amountPaise").value(3_000))
                .andReturn();
        long transferId = TestApi.readLong(result, "$.id");

        assertThat(api.balance(alice)).isEqualTo(7_000);
        assertThat(api.balance(bob)).isEqualTo(4_000);
        assertThat(jdbc.queryForList(
                "select amount_paise from ledger_entries where transfer_id = ? order by amount_paise",
                Long.class, transferId)).containsExactly(-3_000L, 3_000L);
        invariants.assertAllHold();
    }

    @Test
    void insufficientFundsIsRejectedAndNothingChanges() throws Exception {
        long alice = api.newAccountWithBalance(500);
        long bob = api.newAccountWithBalance(0);
        long transfersBefore = invariants.transferCount();

        api.transfer(alice, bob, 501)
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.status").value(422));

        assertThat(api.balance(alice)).isEqualTo(500);
        assertThat(api.balance(bob)).isZero();
        assertThat(invariants.transferCount()).as("rolled back: no transfer row").isEqualTo(transfersBefore);
        invariants.assertAllHold();
    }

    @Test
    void transferOfEntireBalanceIsAllowed() throws Exception {
        long alice = api.newAccountWithBalance(500);
        long bob = api.newAccountWithBalance(0);

        api.transfer(alice, bob, 500).andExpect(status().isCreated());

        assertThat(api.balance(alice)).isZero();
        invariants.assertAllHold();
    }

    @Test
    void sameAccountTransferIsBadRequest() throws Exception {
        long alice = api.newAccountWithBalance(500);

        api.transfer(alice, alice, 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT_TRANSFER"));
        assertThat(api.balance(alice)).isEqualTo(500);
    }

    @Test
    void unknownSourceAccountIsNotFound() throws Exception {
        long bob = api.newAccountWithBalance(0);
        api.transfer(999_999, bob, 100)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void unknownDestinationAccountIsNotFound() throws Exception {
        long alice = api.newAccountWithBalance(500);
        api.transfer(alice, 999_999, 100)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        assertThat(api.balance(alice)).isEqualTo(500);
    }

    @Test
    void zeroAmountIsValidationError() throws Exception {
        long alice = api.newAccountWithBalance(500);
        long bob = api.newAccountWithBalance(0);
        api.transfer(alice, bob, 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("amountPaise"));
    }

    @Test
    void negativeAmountIsValidationError() throws Exception {
        long alice = api.newAccountWithBalance(500);
        long bob = api.newAccountWithBalance(0);
        api.transfer(alice, bob, -100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(api.balance(alice)).isEqualTo(500);
        assertThat(api.balance(bob)).isZero();
    }

    @Test
    void fractionalAmountIsRejectedNotTruncated() throws Exception {
        long alice = api.newAccountWithBalance(500);
        long bob = api.newAccountWithBalance(0);
        api.postJson("/api/transfers", """
                        {"fromAccountId": %d, "toAccountId": %d, "amountPaise": 10.5}""".formatted(alice, bob))
                .andExpect(status().isBadRequest());
        assertThat(api.balance(alice)).isEqualTo(500);
    }

    @Test
    void amountAboveLimitIsValidationError() throws Exception {
        long alice = api.newAccountWithBalance(500);
        long bob = api.newAccountWithBalance(0);
        api.transfer(alice, bob, 10_000_000_001L)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void missingFieldIsValidationError() throws Exception {
        api.postJson("/api/transfers", """
                        {"fromAccountId": 1, "amountPaise": 100}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("toAccountId"));
    }

    @Test
    void malformedJsonIsProblemDetail() throws Exception {
        api.postJson("/api/transfers", "{not json")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void cannotTransferIntoOrOutOfTheFundingAccount() throws Exception {
        long funding = jdbc.queryForObject("select id from accounts where kind = 'FUNDING'", Long.class);
        long alice = api.newAccountWithBalance(500);

        api.transfer(funding, alice, 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ELIGIBLE"));
        api.transfer(alice, funding, 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ELIGIBLE"));
        assertThat(api.balance(alice)).isEqualTo(500);
        invariants.assertAllHold();
    }

    @Test
    void listsTransfersForAnAccountNewestFirstWithPaging() throws Exception {
        long alice = api.newAccountWithBalance(10_000);
        long bob = api.newAccountWithBalance(0);
        long carol = api.newAccountWithBalance(0);
        api.transfer(alice, bob, 100).andExpect(status().isCreated());
        api.transfer(alice, carol, 200).andExpect(status().isCreated());
        api.transfer(bob, carol, 50).andExpect(status().isCreated());

        api.getJson("/api/transfers?accountId=" + alice + "&size=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3)) // deposit + 2 transfers
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].amountPaise").value(200))
                .andExpect(jsonPath("$.items[1].amountPaise").value(100));

        api.getJson("/api/transfers?accountId=" + carol)
                .andExpect(jsonPath("$.totalItems").value(2));
    }

    @Test
    void getsTransferByIdAndReportsUnknownOnes() throws Exception {
        long alice = api.newAccountWithBalance(1_000);
        long bob = api.newAccountWithBalance(0);
        long id = TestApi.readLong(api.transfer(alice, bob, 300).andReturn(), "$.id");

        api.getJson("/api/transfers/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountPaise").value(300));
        api.getJson("/api/transfers/777777")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }
}
