package com.example.ledger.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

/**
 * Kafka beans, only when {@code ledger.kafka.enabled=true}. The {@code core} profile disables
 * Kafka entirely (and excludes Spring Boot's Kafka auto-configuration).
 */
@Configuration
@ConditionalOnProperty(prefix = "ledger.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConfig {

    /** Spring's KafkaAdmin creates these topics at startup if they don't exist yet. */
    @Bean
    public NewTopic transferCompletedTopic(LedgerKafkaProperties properties) {
        return TopicBuilder.name(properties.topic()).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic transferCompletedDeadLetterTopic(LedgerKafkaProperties properties) {
        return TopicBuilder.name(properties.topic() + "-dlt").partitions(3).replicas(1).build();
    }

    /**
     * What the consumer does when handling a message throws: retry 5 times, 1 second apart (e.g.
     * the database is briefly unavailable), then publish the message to {@code <topic>-dlt} (a
     * dead-letter topic) for a human to inspect, and move on. A malformed JSON message will never
     * succeed, so it skips the retries. Spring Boot wires this bean into the listener container.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(kafkaTemplate), new FixedBackOff(1_000L, 5));
        handler.addNotRetryableExceptions(JacksonException.class);
        return handler;
    }
}
