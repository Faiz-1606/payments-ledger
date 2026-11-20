package com.example.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.example.ledger.support.AbstractIntegrationTest;

/** The scheduled job really runs: schedule it every 200 ms and wait for a report to appear. */
@TestPropertySource(properties = {
        "ledger.reconciliation.enabled=true",
        "ledger.reconciliation.interval=PT0.2S",
        "ledger.reconciliation.initial-delay=PT0S"
})
class ReconciliationJobTest extends AbstractIntegrationTest {

    @Autowired
    ReconciliationService reconciliationService;

    @Autowired
    ReconciliationJob reconciliationJob; // exists only because enabled=true

    @Test
    void scheduledJobProducesReports() {
        assertThat(reconciliationJob).isNotNull();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(reconciliationService.latest()).isPresent());
    }
}
