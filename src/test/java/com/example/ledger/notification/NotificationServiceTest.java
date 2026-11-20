package com.example.ledger.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.ledger.outbox.TransferCompletedEvent;
import com.example.ledger.support.AbstractIntegrationTest;

/** The consumer's logic, called directly (no Kafka needed to prove deduplication). */
class NotificationServiceTest extends AbstractIntegrationTest {

    @Autowired
    NotificationService notificationService;

    @Test
    void transferNotifiesSenderAndReceiver() throws Exception {
        long alice = api.newAccountWithBalance(0);
        long bob = api.newAccountWithBalance(0);

        boolean processed = notificationService.handle(event(alice, bob, "TRANSFER", 12_345));

        assertThat(processed).isTrue();
        api.getJsonAs(api.tokenForAccount(alice), "/api/notifications")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].message").value("You sent ₹123.45 to account " + bob + " (transfer 99)"));
        api.getJsonAs(api.tokenForAccount(bob), "/api/notifications")
                .andExpect(jsonPath("$[0].message").value("You received ₹123.45 from account " + alice + " (transfer 99)"));
    }

    @Test
    void sameEventDeliveredTwiceIsProcessedOnce() throws Exception {
        long alice = api.newAccountWithBalance(0);
        long bob = api.newAccountWithBalance(0);
        TransferCompletedEvent event = event(alice, bob, "TRANSFER", 500);

        assertThat(notificationService.handle(event)).isTrue();
        assertThat(notificationService.handle(event)).as("duplicate").isFalse();
        assertThat(notificationService.handle(event)).as("duplicate").isFalse();

        assertThat(jdbc.queryForObject("select count(*) from notifications where event_id = ?", Long.class,
                event.eventId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from processed_events", Long.class)).isEqualTo(1);
    }

    @Test
    void depositNotifiesOnlyTheRecipient() throws Exception {
        long funding = jdbc.queryForObject("select id from accounts where kind = 'FUNDING'", Long.class);
        long alice = api.newAccountWithBalance(0);

        notificationService.handle(event(funding, alice, "DEPOSIT", 10_000));

        api.getJsonAs(api.tokenForAccount(alice), "/api/notifications")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].message").value("₹100.00 was deposited into account " + alice + " (transfer 99)"));
    }

    private static TransferCompletedEvent event(long from, long to, String type, long amount) {
        return new TransferCompletedEvent(UUID.randomUUID(), TransferCompletedEvent.TYPE, 99, type, from, to, amount,
                Instant.now());
    }
}
