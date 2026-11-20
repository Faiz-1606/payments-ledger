package com.example.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.example.ledger.support.AbstractIntegrationTest;
import com.example.ledger.support.TestApi;

/** Invariant 5: a request retried with the same Idempotency-Key never moves money twice. */
class IdempotencyApiTest extends AbstractIntegrationTest {

    @Test
    void transferWithoutIdempotencyKeyIsRejected() throws Exception {
        long alice = api.newAccountWithBalance(1_000);
        long bob = api.newAccountWithBalance(0);
        long transfersBefore = invariants.transferCount();

        api.transfer(null, alice, bob, 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
        assertThat(invariants.transferCount()).isEqualTo(transfersBefore);
    }

    @Test
    void depositWithoutIdempotencyKeyIsRejected() throws Exception {
        long alice = api.newAccountWithBalance(0);
        api.deposit(null, alice, 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
        assertThat(api.balance(alice)).isZero();
    }

    @Test
    void malformedIdempotencyKeyIsRejected() throws Exception {
        long alice = api.newAccountWithBalance(1_000);
        long bob = api.newAccountWithBalance(0);
        api.transfer("has spaces and !", alice, bob, 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        api.transfer("k".repeat(101), alice, bob, 100)
                .andExpect(status().isBadRequest());
        assertThat(api.balance(alice)).isEqualTo(1_000);
    }

    @Test
    void sameKeyAndPayloadReturnsOriginalResponseWithoutMovingMoneyAgain() throws Exception {
        long alice = api.newAccountWithBalance(10_000);
        long bob = api.newAccountWithBalance(0);
        String key = TestApi.newKey();

        MvcResult first = api.transfer(key, alice, bob, 2_500)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andReturn();
        MvcResult retry = api.transfer(key, alice, bob, 2_500)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(retry.getResponse().getContentAsString())
                .as("replayed body is byte-for-byte the original")
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(api.balance(alice)).isEqualTo(7_500);
        assertThat(api.balance(bob)).isEqualTo(2_500);
        assertThat(jdbc.queryForObject("select count(*) from transfers where type = 'TRANSFER'", Long.class))
                .isEqualTo(1);
        invariants.assertAllHold();
    }

    @Test
    void sameKeyWithDifferentPayloadIs422AndMovesNothing() throws Exception {
        long alice = api.newAccountWithBalance(10_000);
        long bob = api.newAccountWithBalance(0);
        String key = TestApi.newKey();
        api.transfer(key, alice, bob, 1_000).andExpect(status().isCreated());

        api.transfer(key, alice, bob, 9_000)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(api.balance(alice)).isEqualTo(9_000);
        assertThat(api.balance(bob)).isEqualTo(1_000);
        invariants.assertAllHold();
    }

    @Test
    void keyUsedForDepositCannotBeReusedForTransfer() throws Exception {
        long alice = api.newAccountWithBalance(0);
        long bob = api.newAccountWithBalance(0);
        String key = TestApi.newKey();
        api.deposit(key, alice, 5_000).andExpect(status().isCreated());

        api.transfer(key, alice, bob, 5_000)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(api.balance(bob)).isZero();
    }

    @Test
    void depositRetriedWithSameKeyCreditsOnce() throws Exception {
        long alice = api.newAccountWithBalance(0);
        String key = TestApi.newKey();

        api.deposit(key, alice, 5_000).andExpect(status().isCreated());
        api.deposit(key, alice, 5_000)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        api.deposit(key, alice, 5_000).andExpect(status().isCreated());

        assertThat(api.balance(alice)).isEqualTo(5_000);
        invariants.assertAllHold();
    }

    @Test
    void failedRequestIsNotStoredSoTheClientCanRetryWithTheSameKey() throws Exception {
        long alice = api.newAccountWithBalance(100);
        long bob = api.newAccountWithBalance(0);
        String key = TestApi.newKey();

        api.transfer(key, alice, bob, 500).andExpect(status().isUnprocessableContent());
        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys where idempotency_key = ?",
                Long.class, key)).as("key rolled back together with the failed transfer").isZero();

        api.deposit(alice, 1_000).andExpect(status().isCreated());
        api.transfer(key, alice, bob, 500)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"));

        assertThat(api.balance(bob)).isEqualTo(500);
        invariants.assertAllHold();
    }

    @Test
    void keyIsStoredWithRequestHashTransferAndResponse() throws Exception {
        long alice = api.newAccountWithBalance(1_000);
        long bob = api.newAccountWithBalance(0);
        String key = TestApi.newKey();
        long transferId = TestApi.readLong(api.transfer(key, alice, bob, 300).andReturn(), "$.id");

        Map<String, Object> row = jdbc.queryForMap(
                "select request_hash, transfer_id, response_body from idempotency_keys where idempotency_key = ?",
                key);
        assertThat((String) row.get("request_hash")).matches("[0-9a-f]{64}");
        assertThat(((Number) row.get("transfer_id")).longValue()).isEqualTo(transferId);
        assertThat((String) row.get("response_body")).contains("\"id\":" + transferId);
        assertThat(jdbc.queryForObject("select idempotency_key from transfers where id = ?", String.class, transferId))
                .isEqualTo(key);
    }
}
