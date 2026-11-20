package com.example.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;

import com.example.ledger.notification.TransferEventListener;
import com.example.ledger.support.AbstractIntegrationTest;
import com.example.ledger.support.TestApi;

/**
 * The producer half of the outbox pattern, in the core profile (no Kafka at all): the event row
 * is written by the transfer's own transaction, or not at all.
 */
class OutboxTest extends AbstractIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void completedTransferWritesExactlyOneEventWithItsDetails() throws Exception {
        long alice = api.newAccountWithBalance(10_000);
        long bob = api.newAccountWithBalance(0);
        long transferId = TestApi.readLong(api.transfer(alice, bob, 2_500).andReturn(), "$.id");

        Map<String, Object> row = jdbc.queryForMap(
                "select event_type, payload, published_at from outbox_events where aggregate_id = ?",
                Long.toString(transferId));
        assertThat(row.get("event_type")).isEqualTo("TransferCompleted");
        assertThat(row.get("published_at")).as("core profile: nothing publishes").isNull();
        String payload = (String) row.get("payload");
        assertThat(((Number) JsonPath.read(payload, "$.transferId")).longValue()).isEqualTo(transferId);
        assertThat(((Number) JsonPath.read(payload, "$.amountPaise")).longValue()).isEqualTo(2_500);
        assertThat((String) JsonPath.read(payload, "$.transferType")).isEqualTo("TRANSFER");
        assertThat((String) JsonPath.read(payload, "$.eventId")).isNotBlank();
    }

    @Test
    void failedTransferWritesNoEvent() throws Exception {
        long alice = api.newAccountWithBalance(100);
        long bob = api.newAccountWithBalance(0);
        long eventsBefore = outboxCount();

        api.transfer(alice, bob, 5_000).andExpect(status().isUnprocessableContent());

        assertThat(outboxCount()).as("the rollback removed the event with the transfer").isEqualTo(eventsBefore);
    }

    @Test
    void idempotentReplayWritesNoSecondEvent() throws Exception {
        long alice = api.newAccountWithBalance(1_000);
        long bob = api.newAccountWithBalance(0);
        String key = TestApi.newKey();
        api.transfer(key, alice, bob, 100).andExpect(status().isCreated());
        long eventsAfterFirst = outboxCount();

        api.transfer(key, alice, bob, 100).andExpect(status().isCreated());

        assertThat(outboxCount()).isEqualTo(eventsAfterFirst);
    }

    @Test
    void everyCompletedTransferHasExactlyOneEvent() throws Exception {
        long alice = api.newAccountWithBalance(10_000);
        long bob = api.newAccountWithBalance(10_000);
        api.transfer(alice, bob, 1).andExpect(status().isCreated());
        api.transfer(bob, alice, 2).andExpect(status().isCreated());
        api.deposit(alice, 3).andExpect(status().isCreated());

        assertThat(outboxCount()).isEqualTo(invariants.transferCount());
        assertThat(jdbc.queryForObject("""
                select count(*) from transfers t
                where (select count(*) from outbox_events o where o.aggregate_id = t.id::text) <> 1""", Long.class))
                .isZero();
    }

    @Test
    void coreProfileHasNoKafkaBeans() {
        assertThat(context.getBeanProvider(OutboxPublisher.class).getIfAvailable()).isNull();
        assertThat(context.getBeanProvider(TransferEventListener.class).getIfAvailable()).isNull();
        assertThat(context.getBeanProvider(KafkaTemplate.class).getIfAvailable()).isNull();
    }

    @Test
    void adminCanSeeOutboxBacklog() throws Exception {
        long alice = api.newAccountWithBalance(1_000);
        api.transfer(alice, api.newAccountWithBalance(0), 10).andExpect(status().isCreated());

        api.getJson("/api/admin/outbox")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(2)) // alice's opening deposit + the transfer
                .andExpect(jsonPath("$.published").value(0));
        api.getJsonAs(api.tokenForAccount(alice), "/api/admin/outbox").andExpect(status().isForbidden());
    }

    private long outboxCount() {
        return jdbc.queryForObject("select count(*) from outbox_events", Long.class);
    }
}
