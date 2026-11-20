package com.example.ledger.outbox;

import java.time.Instant;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN only (/api/admin/**): how far behind is the outbox publisher? */
@RestController
@RequestMapping("/api/admin/outbox")
public class OutboxController {

    private final OutboxRepository outboxRepository;

    public OutboxController(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public OutboxStatus status() {
        return new OutboxStatus(outboxRepository.countByPublishedAtIsNull(),
                outboxRepository.countByPublishedAtIsNotNull(),
                outboxRepository.oldestUnpublishedCreatedAt().orElse(null));
    }

    public record OutboxStatus(long pending, long published, Instant oldestPendingCreatedAt) {
    }
}
