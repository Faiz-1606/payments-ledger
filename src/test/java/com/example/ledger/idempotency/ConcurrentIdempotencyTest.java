package com.example.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.example.ledger.support.AbstractIntegrationTest;
import com.example.ledger.support.TestApi;
import com.example.ledger.transfer.TransferOutcome;
import com.example.ledger.transfer.TransferService;

/**
 * Many requests with the same Idempotency-Key at the same instant. A Java-level check
 * ("does the key exist? no -> transfer") would let several through; the PRIMARY KEY on
 * idempotency_keys lets exactly one through.
 */
class ConcurrentIdempotencyTest extends AbstractIntegrationTest {

    private static final int THREADS = 20;

    @Autowired
    TransferService transferService;

    @Test
    void concurrentDuplicatesProduceExactlyOneTransfer() throws Exception {
        long alice = api.newAccountWithBalance(100_000);
        long bob = api.newAccountWithBalance(0);
        String key = TestApi.newKey();

        List<TransferOutcome> outcomes = runConcurrently(THREADS,
                () -> transferService.transfer(key, alice, bob, 1_000));

        assertThat(outcomes).extracting(o -> o.response().id()).containsOnly(outcomes.getFirst().response().id());
        assertThat(outcomes).filteredOn(o -> !o.replayed()).hasSize(1);
        assertThat(outcomes).filteredOn(TransferOutcome::replayed).hasSize(THREADS - 1);
        assertThat(invariants.storedBalance(alice)).isEqualTo(99_000);
        assertThat(invariants.storedBalance(bob)).isEqualTo(1_000);
        assertThat(jdbc.queryForObject("select count(*) from transfers where idempotency_key = ?", Long.class, key))
                .isEqualTo(1);
        invariants.assertAllHold();
    }

    @Test
    void concurrentDuplicatesOverHttpProduceExactlyOneTransfer() throws Exception {
        long alice = api.newAccountWithBalance(100_000);
        long bob = api.newAccountWithBalance(0);
        String key = TestApi.newKey();

        List<MvcResult> results = runConcurrently(10,
                () -> api.transfer(key, alice, bob, 2_000).andReturn());

        assertThat(results).extracting(r -> r.getResponse().getStatus()).containsOnly(201);
        assertThat(results).extracting(r -> r.getResponse().getHeader("Idempotent-Replayed"))
                .containsOnlyOnce("false");
        assertThat(invariants.storedBalance(bob)).isEqualTo(2_000);
        invariants.assertAllHold();
    }

    @Test
    void concurrentSameKeyDifferentPayloadsOnlyOneWins() throws Exception {
        long alice = api.newAccountWithBalance(100_000);
        long bob = api.newAccountWithBalance(0);
        String key = TestApi.newKey();

        // Half the threads send 1_000, the other half send 3_000, all with the same key.
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long amount = i % 2 == 0 ? 1_000 : 3_000;
            tasks.add(() -> api.transfer(key, alice, bob, amount).andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = runAll(tasks);

        long moved = invariants.storedBalance(bob);
        assertThat(moved).as("exactly one of the two payloads executed, once").isIn(1_000L, 3_000L);
        // The 5 requests matching the winner get 201 (1 fresh + 4 replays); the other 5 get 422.
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(5);
        assertThat(statuses).filteredOn(s -> s == 422).hasSize(5);
        invariants.assertAllHold();
    }

    private static <T> List<T> runConcurrently(int count, Callable<T> task) throws Exception {
        List<Callable<T>> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tasks.add(task);
        }
        return runAll(tasks);
    }

    /** Starts all tasks at the same moment (a latch acts as the starting gun) and waits for them. */
    private static <T> List<T> runAll(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch startGun = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    startGun.await();
                    return task.call();
                }));
            }
            startGun.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
