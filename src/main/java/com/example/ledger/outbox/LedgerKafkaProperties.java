package com.example.ledger.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code ledger.kafka.*}: whether the app talks to Kafka at all, and how the outbox publisher
 * behaves. The {@code core} profile sets {@code enabled: false}.
 *
 * @param publishInterval  pause between outbox polls (fixed delay)
 * @param publishBatchSize rows claimed per publisher transaction
 * @param sendTimeout      how long to wait for Kafka to acknowledge one event
 */
@ConfigurationProperties(prefix = "ledger.kafka")
public record LedgerKafkaProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("ledger.transfer-completed") String topic,
        @DefaultValue("500ms") Duration publishInterval,
        @DefaultValue("100") int publishBatchSize,
        @DefaultValue("5s") Duration sendTimeout) {
}
