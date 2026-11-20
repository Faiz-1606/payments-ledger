package com.example.ledger.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs reconciliation on a schedule ({@code ledger.reconciliation.interval}, default every 5
 * minutes). {@code fixedDelay} means "wait this long after the previous run <em>finished</em>",
 * so slow runs never overlap.
 *
 * <p>With several app instances, each would run it. That is harmless (it is read-only), just
 * wasted work; a distributed lock such as ShedLock would make it run once per cluster.
 */
@Component
@ConditionalOnProperty(prefix = "ledger.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService reconciliationService;

    public ReconciliationJob(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(fixedDelayString = "${ledger.reconciliation.interval:PT5M}",
            initialDelayString = "${ledger.reconciliation.initial-delay:PT30S}")
    public void reconcile() {
        try {
            reconciliationService.run();
        } catch (RuntimeException e) {
            // An exception would only be logged by the scheduler anyway; say what failed.
            log.error("Scheduled reconciliation failed", e);
        }
    }
}
