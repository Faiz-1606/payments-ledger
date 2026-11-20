package com.example.ledger.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import com.example.ledger.support.AbstractIntegrationTest;
import com.example.ledger.support.TestApi;
import com.example.ledger.transfer.TransferCommand;
import com.example.ledger.transfer.TransferService;
import com.example.ledger.transfer.TransferType;
import com.example.ledger.transfer.locking.LockingMode;

class ObservabilityTest extends AbstractIntegrationTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    TransferService transferService;

    // ---- request id ----------------------------------------------------------------------

    @Test
    void everyResponseCarriesAGeneratedRequestId() throws Exception {
        api.getJsonAnonymous("/actuator/health")
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern(UUID_PATTERN)));
    }

    @Test
    void wellFormedIncomingRequestIdIsPropagated() throws Exception {
        api.perform(get("/actuator/health").header(RequestIdFilter.HEADER, "lb-abc_123"))
                .andExpect(header().string(RequestIdFilter.HEADER, "lb-abc_123"));
    }

    @Test
    void malformedIncomingRequestIdIsReplaced() throws Exception {
        api.perform(get("/actuator/health").header(RequestIdFilter.HEADER, "bad id\nwith newline"))
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern(UUID_PATTERN)));
    }

    @Test
    void errorBodiesIncludeTheRequestId() throws Exception {
        api.perform(get("/api/accounts/999999")
                        .header(HttpHeaders.AUTHORIZATION, TestApi.bearer(api.adminToken()))
                        .header(RequestIdFilter.HEADER, "trace-me-42"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.requestId").value("trace-me-42"));
        // Errors raised inside the security filter chain get it too.
        api.perform(get("/api/accounts/1").header(RequestIdFilter.HEADER, "trace-me-43"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.requestId").value("trace-me-43"));
    }

    // ---- custom metrics --------------------------------------------------------------------

    @Test
    void transfersAreCountedAndTimedByOutcome() throws Exception {
        double completedBefore = count("ledger.transfers", "type", "TRANSFER", "outcome", "completed");
        double rejectedBefore = count("ledger.transfers", "type", "TRANSFER", "outcome", "insufficient_funds");
        long alice = api.newAccountWithBalance(1_000);
        long bob = api.newAccountWithBalance(0);

        api.transfer(alice, bob, 100).andExpect(status().isCreated());
        api.transfer(alice, bob, 1_000_000).andExpect(status().isUnprocessableContent());

        assertThat(count("ledger.transfers", "type", "TRANSFER", "outcome", "completed")).isEqualTo(completedBefore + 1);
        assertThat(count("ledger.transfers", "type", "TRANSFER", "outcome", "insufficient_funds"))
                .isEqualTo(rejectedBefore + 1);
        assertThat(meterRegistry.get("ledger.transfer.duration").tag("outcome", "completed").timer().count())
                .isPositive();
    }

    @Test
    void idempotentReplaysAreCounted() throws Exception {
        double before = count("ledger.idempotency.replays", "type", "DEPOSIT");
        long alice = api.newAccountWithBalance(0);
        String key = TestApi.newKey();
        api.deposit(key, alice, 500).andExpect(status().isCreated());
        api.deposit(key, alice, 500).andExpect(status().isCreated());
        api.deposit(key, alice, 500).andExpect(status().isCreated());

        assertThat(count("ledger.idempotency.replays", "type", "DEPOSIT")).isEqualTo(before + 2);
    }

    @Test
    void optimisticLockRetriesAreCounted() throws Exception {
        double before = count("ledger.lock.retries", "strategy", "optimistic");
        long a = api.newAccountWithBalance(1_000_000);
        long b = api.newAccountWithBalance(1_000_000);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                TransferCommand command = new TransferCommand(TransferType.TRANSFER, i % 2 == 0 ? a : b,
                        i % 2 == 0 ? b : a, 10);
                futures.add(pool.submit(() -> transferService.execute(UUID.randomUUID().toString(), command,
                        LockingMode.OPTIMISTIC)));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception ignored) {
                    // some may exhaust their retries; that is fine here
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("ledger.lock.retries", "strategy", "optimistic")).isGreaterThan(before);
    }

    // ---- actuator ----------------------------------------------------------------------------

    @Test
    void healthIsPublic() throws Exception {
        api.getJsonAnonymous("/actuator/health")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        api.getJsonAnonymous("/actuator/health/readiness").andExpect(status().isOk());
    }

    @Test
    void prometheusScrapeExposesLedgerMetricsToAdminsOnly() throws Exception {
        long alice = api.newAccountWithBalance(1_000);
        api.transfer(alice, api.newAccountWithBalance(0), 10).andExpect(status().isCreated());

        api.getJson("/actuator/prometheus")
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ledger_transfers_total")))
                .andExpect(content().string(containsString("ledger_transfer_duration_seconds_bucket")));
        api.getJsonAs(api.tokenForAccount(alice), "/actuator/prometheus").andExpect(status().isForbidden());
        api.getJsonAnonymous("/actuator/prometheus").andExpect(status().isUnauthorized());
    }

    private double count(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }
}
