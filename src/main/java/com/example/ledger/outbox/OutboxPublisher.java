package com.example.ledger.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves events from the outbox table to Kafka.
 *
 * <p>Every {@code ledger.kafka.publish-interval}: in one transaction, claim up to N unpublished
 * rows ({@code FOR UPDATE SKIP LOCKED}), send each to Kafka and wait for the broker's
 * acknowledgement, mark it published, commit. If a send fails (Kafka down), the row stays
 * unpublished with {@code attempts} incremented, and the next poll tries again.
 *
 * <p><b>Delivery is at-least-once, not exactly-once.</b> If the app crashes after Kafka
 * acknowledged a send but before the "published" mark commits, the event is sent again after a
 * restart. That is why the consumer deduplicates by event id. Exactly-once across a database
 * and Kafka is not achievable without both joining one transaction; at-least-once plus an
 * idempotent consumer gives the same observable result.
 */
@Component
@ConditionalOnProperty(prefix = "ledger.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final LedgerKafkaProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Counter published;
    private final Counter failures;
    private final AtomicLong pending = new AtomicLong();

    public OutboxPublisher(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate,
                           LedgerKafkaProperties properties, PlatformTransactionManager transactionManager,
                           MeterRegistry registry) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.published = registry.counter("ledger.outbox.published");
        this.failures = registry.counter("ledger.outbox.publish.failures");
        Gauge.builder("ledger.outbox.pending", pending, AtomicLong::get)
                .description("Outbox events not yet acknowledged by Kafka (as of the last poll)")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${ledger.kafka.publish-interval:PT0.5S}")
    public void publishPending() {
        int sent;
        do {
            sent = publishOneBatch();
        } while (sent == properties.publishBatchSize()); // a full batch: there may be more
        pending.set(outboxRepository.countByPublishedAtIsNull());
    }

    /** One transaction: claim a batch, send it, mark what was sent. Returns how many were sent. */
    int publishOneBatch() {
        Integer sent = transactionTemplate.execute(status -> {
            List<OutboxEvent> batch = outboxRepository.lockNextUnpublished(properties.publishBatchSize());
            int count = 0;
            for (OutboxEvent event : batch) {
                try {
                    send(event);
                    event.markPublished(Instant.now()); // dirty checking writes this at commit
                    published.increment();
                    count++;
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    event.recordFailure(e.toString());
                    failures.increment();
                    log.warn("Could not publish outbox event {} (attempt {}): {}. Will retry.",
                            event.getId(), event.getAttempts(), e.toString());
                    break; // the broker is probably unavailable; stop and retry on the next poll
                }
            }
            return count;
        });
        return sent == null ? 0 : sent;
    }

    /**
     * Synchronous send: {@code get()} waits until the broker acknowledged the write (acks=all),
     * so "marked published" really means "Kafka has it". The transfer id is the message key, and
     * the event id travels as a header as well as in the payload.
     */
    private void send(OutboxEvent event) throws Exception {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(properties.topic(), event.getAggregateId(), event.getPayload());
        record.headers().add("eventId", event.getId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }
}
