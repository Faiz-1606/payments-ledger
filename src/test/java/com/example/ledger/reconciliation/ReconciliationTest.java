package com.example.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

class ReconciliationTest extends AbstractIntegrationTest {

    @Autowired
    ReconciliationService reconciliationService;

    @Autowired
    TransferService transferService;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void healthyLedgerProducesACleanReport() throws Exception {
        long alice = api.newAccountWithBalance(10_000);
        long bob = api.newAccountWithBalance(0);
        api.transfer(alice, bob, 2_500);

        ReconciliationReport report = reconciliationService.run();

        assertThat(report.healthy()).isTrue();
        assertThat(report.ledgerTotalPaise()).isZero();
        assertThat(report.driftedAccounts()).isEmpty();
        assertThat(report.unbalancedTransferIds()).isEmpty();
        assertThat(report.negativeAccountIds()).isEmpty();
        assertThat(report.accountsChecked()).isEqualTo(3); // funding + alice + bob
        assertThat(report.transfersChecked()).isEqualTo(2);
        assertThat(report.ledgerEntriesChecked()).isEqualTo(4);
    }

    @Test
    void tamperedBalanceIsReportedAsDrift() throws Exception {
        long alice = api.newAccountWithBalance(10_000);
        // Someone "fixes" a balance by hand, bypassing the ledger.
        jdbc.update("UPDATE accounts SET balance_paise = balance_paise + 500 WHERE id = ?", alice);

        ReconciliationReport report = reconciliationService.run();

        assertThat(report.healthy()).isFalse();
        assertThat(report.ledgerTotalPaise()).as("the ledger itself is still balanced").isZero();
        assertThat(report.driftedAccounts()).singleElement().satisfies(drift -> {
            assertThat(drift.accountId()).isEqualTo(alice);
            assertThat(drift.storedBalancePaise()).isEqualTo(10_500);
            assertThat(drift.ledgerBalancePaise()).isEqualTo(10_000);
            assertThat(drift.driftPaise()).isEqualTo(500);
        });
        assertThat(report.totalAbsoluteDriftPaise()).isEqualTo(500);
        assertThat(meterRegistry.get("ledger.reconciliation.drifted.accounts").gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.get("ledger.reconciliation.healthy").gauge().value()).isZero();
    }

    @Test
    void halfWrittenTransferBreaksInvariantsOneAndThree() throws Exception {
        long alice = api.newAccountWithBalance(1_000);
        long bob = api.newAccountWithBalance(0);
        // Simulate a bug that wrote a transfer with only its debit line.
        Long transferId = jdbc.queryForObject("""
                INSERT INTO transfers (type, from_account_id, to_account_id, amount_paise, status)
                VALUES ('TRANSFER', ?, ?, 300, 'COMPLETED') RETURNING id""", Long.class, alice, bob);
        jdbc.update("INSERT INTO ledger_entries (transfer_id, account_id, amount_paise) VALUES (?, ?, -300)",
                transferId, alice);

        ReconciliationReport report = reconciliationService.run();

        assertThat(report.healthy()).isFalse();
        assertThat(report.unbalancedTransferIds()).containsExactly(transferId);
        assertThat(report.ledgerTotalPaise()).isEqualTo(-300);
        assertThat(report.driftedAccounts()).extracting(ReconciliationReport.AccountDrift::accountId)
                .containsExactly(alice);
    }

    @Test
    void reconciliationCatchesWhatNaiveLockingDid() throws Exception {
        long a = api.newAccountWithBalance(1_000_000);
        long b = api.newAccountWithBalance(1_000_000);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                TransferCommand command = i % 2 == 0
                        ? new TransferCommand(TransferType.TRANSFER, a, b, 100 + i)
                        : new TransferCommand(TransferType.TRANSFER, b, a, 100 + i);
                futures.add(pool.submit(() -> transferService.execute(UUID.randomUUID().toString(), command,
                        LockingMode.NAIVE)));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception ignored) {
                    // naive mode may fail some transfers; we only care about the damage it leaves
                }
            }
        } finally {
            pool.shutdownNow();
        }

        ReconciliationReport report = reconciliationService.run();

        assertThat(report.healthy()).isFalse();
        assertThat(report.driftedAccounts()).isNotEmpty();
        assertThat(report.ledgerTotalPaise()).as("double entry itself is intact").isZero();
    }

    @Test
    void adminCanRunReconciliationOverHttpAndFetchTheLatestReport() throws Exception {
        api.newAccountWithBalance(5_000);

        api.perform(post("/api/admin/reconciliation/runs")
                        .header(HttpHeaders.AUTHORIZATION, TestApi.bearer(api.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true))
                .andExpect(jsonPath("$.ledgerTotalPaise").value(0))
                .andExpect(jsonPath("$.driftedAccounts.length()").value(0));

        api.getJson("/api/admin/reconciliation/latest")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountsChecked").value(2));
    }

    @Test
    void reconciliationEndpointsAreAdminOnly() throws Exception {
        TestApi.TestUser user = api.registerUser();
        api.perform(post("/api/admin/reconciliation/runs").header(HttpHeaders.AUTHORIZATION, TestApi.bearer(user.token())))
                .andExpect(status().isForbidden());
        api.getJsonAs(user.token(), "/api/admin/reconciliation/latest").andExpect(status().isForbidden());
        api.perform(post("/api/admin/reconciliation/runs")).andExpect(status().isUnauthorized());
    }
}
