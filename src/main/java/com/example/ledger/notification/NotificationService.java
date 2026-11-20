package com.example.ledger.notification;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ledger.account.Account;
import com.example.ledger.account.AccountKind;
import com.example.ledger.account.AccountRepository;
import com.example.ledger.common.Money;
import com.example.ledger.outbox.TransferCompletedEvent;

/**
 * The idempotent consumer: turns a TransferCompleted event into notifications, at most once per
 * event id, however many times Kafka delivers it.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final ProcessedEventRepository processedEventRepository;
    private final NotificationRepository notificationRepository;
    private final AccountRepository accountRepository;
    private final MeterRegistry registry;

    public NotificationService(ProcessedEventRepository processedEventRepository,
                               NotificationRepository notificationRepository,
                               AccountRepository accountRepository, MeterRegistry registry) {
        this.processedEventRepository = processedEventRepository;
        this.notificationRepository = notificationRepository;
        this.accountRepository = accountRepository;
        this.registry = registry;
    }

    /**
     * One transaction: mark the event processed AND write its notifications. If anything fails,
     * both roll back and the redelivered message is processed from scratch. If it succeeds and
     * Kafka redelivers anyway (the offset commit was lost), the marker makes it a no-op.
     *
     * @return true if processed now, false if it was a duplicate
     */
    @Transactional
    public boolean handle(TransferCompletedEvent event) {
        if (processedEventRepository.tryMarkProcessed(event.eventId()) == 0) {
            registry.counter("ledger.notifications.events", "result", "duplicate").increment();
            log.info("Ignoring duplicate event {} for transfer {}", event.eventId(), event.transferId());
            return false;
        }

        Map<Long, Account> accounts = accountRepository.findAllById(List.of(event.fromAccountId(), event.toAccountId()))
                .stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String amount = Money.format(event.amountPaise());

        Account from = accounts.get(event.fromAccountId());
        Account to = accounts.get(event.toAccountId());
        if (from != null && from.getKind() != AccountKind.FUNDING && from.getOwnerId() != null) {
            notificationRepository.save(new Notification(event.eventId(), from.getOwnerId(), from.getId(),
                    "You sent " + amount + " to account " + event.toAccountId()
                            + " (transfer " + event.transferId() + ")", now));
        }
        if (to != null && to.getOwnerId() != null) {
            String text = "DEPOSIT".equals(event.transferType())
                    ? amount + " was deposited into account " + to.getId() + " (transfer " + event.transferId() + ")"
                    : "You received " + amount + " from account " + event.fromAccountId()
                            + " (transfer " + event.transferId() + ")";
            notificationRepository.save(new Notification(event.eventId(), to.getOwnerId(), to.getId(), text, now));
        }
        registry.counter("ledger.notifications.events", "result", "processed").increment();
        return true;
    }

    @Transactional(readOnly = true)
    public List<Notification> latestFor(long userId) {
        return notificationRepository.findTop50ByUserIdOrderByIdDesc(userId);
    }
}
