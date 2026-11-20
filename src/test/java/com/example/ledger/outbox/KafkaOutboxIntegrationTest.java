package com.example.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.example.ledger.support.AbstractIntegrationTest;
import com.example.ledger.support.TestApi;

/**
 * The full outbox pipeline against a real Kafka broker (Testcontainers):
 * transfer -> outbox row -> publisher -> Kafka -> consumer -> notifications.
 *
 * <p>Uses its own Spring context: the "test" profile without "core", so Kafka is enabled.
 */
@ActiveProfiles(value = "test", inheritProfiles = false)
@TestPropertySource(properties = {
        "ledger.kafka.publish-interval=PT0.2S",
        "ledger.kafka.send-timeout=3s"
})
class KafkaOutboxIntegrationTest extends AbstractIntegrationTest {

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:4.4.0"));

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    LedgerKafkaProperties kafkaProperties;

    /** Let in-flight events finish before the next test's database cleanup. */
    @AfterEach
    void drain() {
        awaitEverythingPublishedAndConsumed();
    }

    @Test
    void transferEventIsPublishedAndConsumedIntoNotifications() throws Exception {
        long alice = api.newAccountWithBalance(10_000);
        long bob = api.newAccountWithBalance(0);
        long transferId = TestApi.readLong(api.transfer(alice, bob, 700).andReturn(), "$.id");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(notificationsForTransfer(transferId)).isEqualTo(2)); // sender + receiver
        assertThat(jdbc.queryForObject("select published_at is not null from outbox_events where aggregate_id = ?",
                Boolean.class, Long.toString(transferId))).isTrue();
    }

    @Test
    void noEventIsLostWhileKafkaIsDown() throws Exception {
        long alice = api.newAccountWithBalance(100_000);
        long bob = api.newAccountWithBalance(0);
        awaitEverythingPublishedAndConsumed();

        List<Long> transferIds = new ArrayList<>();
        pauseKafka();
        try {
            // The money path never touches Kafka, so transfers keep working.
            for (int i = 0; i < 5; i++) {
                transferIds.add(TestApi.readLong(api.transfer(alice, bob, 100 + i)
                        .andExpect(status().isCreated()).andReturn(), "$.id"));
            }
            // The publisher keeps trying and failing; the events wait safely in the outbox.
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject(
                            "select count(*) from outbox_events where published_at is null and attempts > 0",
                            Long.class)).isPositive());
            assertThat(pendingEvents()).isEqualTo(5);
        } finally {
            unpauseKafka();
        }

        // Kafka is back: every event is published and consumed, none lost.
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            assertThat(pendingEvents()).isZero();
            for (long transferId : transferIds) {
                assertThat(notificationsForTransfer(transferId)).isEqualTo(2);
            }
        });
        invariants.assertAllHold();
    }

    @Test
    void republishedEventsAreIgnoredByTheConsumer() throws Exception {
        long alice = api.newAccountWithBalance(10_000);
        long bob = api.newAccountWithBalance(0);
        api.transfer(alice, bob, 300).andExpect(status().isCreated());
        awaitEverythingPublishedAndConsumed();
        long notificationsBefore = count("select count(*) from notifications");
        long events = count("select count(*) from outbox_events");
        double duplicatesBefore = duplicateCount();

        // Simulate "the publisher crashed after Kafka acknowledged, before marking published":
        // every event is sent to Kafka a second time.
        jdbc.update("UPDATE outbox_events SET published_at = NULL");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(duplicateCount() - duplicatesBefore).isEqualTo(events));
        assertThat(count("select count(*) from notifications")).isEqualTo(notificationsBefore);
        assertThat(count("select count(*) from processed_events")).isEqualTo(events);
    }

    @Test
    void sameMessageSentTwiceDirectlyIsProcessedOnce() throws Exception {
        long alice = api.newAccountWithBalance(10_000);
        long bob = api.newAccountWithBalance(0);
        long transferId = TestApi.readLong(api.transfer(alice, bob, 300).andReturn(), "$.id");
        awaitEverythingPublishedAndConsumed();
        String payload = jdbc.queryForObject("select payload from outbox_events where aggregate_id = ?",
                String.class, Long.toString(transferId));
        double duplicatesBefore = duplicateCount();

        kafkaTemplate.send(kafkaProperties.topic(), Long.toString(transferId), payload).get();
        kafkaTemplate.send(kafkaProperties.topic(), Long.toString(transferId), payload).get();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(duplicateCount() - duplicatesBefore).isEqualTo(2));
        assertThat(notificationsForTransfer(transferId)).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------------------

    private void awaitEverythingPublishedAndConsumed() {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(pendingEvents()).isZero();
            assertThat(count("select count(*) from outbox_events o where not exists "
                    + "(select 1 from processed_events p where p.event_id = o.id)")).isZero();
        });
    }

    private long pendingEvents() {
        return count("select count(*) from outbox_events where published_at is null");
    }

    private long notificationsForTransfer(long transferId) {
        return count("select count(*) from notifications n join outbox_events o on o.id = n.event_id "
                + "where o.aggregate_id = '" + transferId + "'");
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private double duplicateCount() {
        Counter counter = meterRegistry.find("ledger.notifications.events").tag("result", "duplicate").counter();
        return counter == null ? 0 : counter.count();
    }

    /** docker pause freezes the broker's processes: connections hang, like a network partition. */
    private static void pauseKafka() {
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    private static void unpauseKafka() {
        KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }
}
