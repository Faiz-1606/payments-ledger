package com.example.ledger.notification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import com.example.ledger.outbox.TransferCompletedEvent;

/**
 * Kafka consumer for TransferCompleted events. Spring Kafka runs a poll loop on its own thread
 * and calls this method once per record. When the method returns normally, the record's offset
 * is committed ({@code ack-mode: record}); when it throws, the error handler in
 * {@code KafkaConfig} retries and finally dead-letters it.
 *
 * <p>The consumer lives in the same application here, but it only talks to the rest of the
 * system through the topic, exactly as a separate notification service would.
 */
@Component
@ConditionalOnProperty(prefix = "ledger.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TransferEventListener {

    private static final Logger log = LoggerFactory.getLogger(TransferEventListener.class);

    private final NotificationService notificationService;
    private final JsonMapper jsonMapper;

    public TransferEventListener(NotificationService notificationService, JsonMapper jsonMapper) {
        this.notificationService = notificationService;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = "${ledger.kafka.topic:ledger.transfer-completed}", groupId = "ledger-notifications")
    public void onTransferCompleted(ConsumerRecord<String, String> record) {
        TransferCompletedEvent event = jsonMapper.readValue(record.value(), TransferCompletedEvent.class);
        boolean processed = notificationService.handle(event);
        log.debug("Event {} (partition {}, offset {}): {}", event.eventId(), record.partition(), record.offset(),
                processed ? "processed" : "duplicate ignored");
    }
}
