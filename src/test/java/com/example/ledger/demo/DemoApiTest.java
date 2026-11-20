package com.example.ledger.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import com.example.ledger.support.AbstractIntegrationTest;
import com.example.ledger.transfer.locking.LockingMode;

/** The race demo, through its public HTTP API, with the demo profile on. */
@ActiveProfiles({"core", "test", "demo"})
class DemoApiTest extends AbstractIntegrationTest {

    @Test
    void stateShowsFreshlyFundedDemoWalletsWithoutAToken() throws Exception {
        api.getJsonAnonymous("/api/demo/state")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts.length()").value(4))
                .andExpect(jsonPath("$.totalStoredPaise").value(400_000))
                .andExpect(jsonPath("$.totalDriftPaise").value(0))
                .andExpect(jsonPath("$.ledgerSumPaise").value(0));
        assertThat(jdbc.queryForObject("select count(*) from accounts where kind = 'DEMO'", Long.class)).isEqualTo(4);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = LockingMode.class, names = {"PESSIMISTIC", "OPTIMISTIC"})
    void safeStrategiesConserveMoneyInTheRace(LockingMode mode) throws Exception {
        MvcResult result = race(mode, 300)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moneyCreatedPaise").value(0))
                .andExpect(jsonPath("$.after.totalDriftPaise").value(0))
                .andExpect(jsonPath("$.after.totalStoredPaise").value(400_000))
                .andExpect(jsonPath("$.otherErrors").value(0))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        System.out.printf("demo %s: %s%n", mode, body.substring(0, Math.min(200, body.length())));
        invariants.assertAllHold();
    }

    @Test
    void naiveStrategyVisiblyBreaksTheDemoWallets() throws Exception {
        String body = race(LockingMode.NAIVE, 500).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long moneyCreated = ((Number) JsonPath.read(body, "$.moneyCreatedPaise")).longValue();
        long drift = ((Number) JsonPath.read(body, "$.after.totalDriftPaise")).longValue();
        System.out.printf("demo NAIVE: moneyCreated=%d drift=%d%n", moneyCreated, drift);

        assertThat(drift).as("stored balances no longer match the ledger").isNotZero();
        assertThat(((Number) JsonPath.read(body, "$.after.ledgerSumPaise")).longValue())
                .as("the ledger itself stays balanced").isZero();
    }

    @Test
    void raceNeverTouchesRealWallets() throws Exception {
        long alice = api.newAccountWithBalance(50_000);

        race(LockingMode.NAIVE, 300).andExpect(status().isOk());

        assertThat(invariants.storedBalance(alice)).isEqualTo(50_000);
        assertThat(invariants.ledgerBalance(alice)).isEqualTo(50_000);
        List<Long> drifted = invariants.accountsWithDrift();
        assertThat(drifted).doesNotContain(alice);
        assertThat(jdbc.queryForList("select kind from accounts where id in (" + join(drifted) + ")", String.class))
                .allMatch("DEMO"::equals);
    }

    @Test
    void resetCreatesAFreshCorrectSet() throws Exception {
        race(LockingMode.NAIVE, 300).andExpect(status().isOk());

        api.postJsonAnonymous("/api/demo/reset", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalStoredPaise").value(400_000))
                .andExpect(jsonPath("$.totalDriftPaise").value(0));
    }

    @Test
    void raceSizeIsBounded() throws Exception {
        race(LockingMode.PESSIMISTIC, 2_001)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RACE"));
        api.postJsonAnonymous("/api/demo/race", """
                        {"mode": "PESSIMISTIC", "transfers": 0}""")
                .andExpect(status().isBadRequest());
    }

    @Test
    void demoWalletsCannotExchangeMoneyWithRealWallets() throws Exception {
        long alice = api.newAccountWithBalance(10_000);
        long demoWallet = ((Number) JsonPath.read(api.getJsonAnonymous("/api/demo/state").andReturn()
                .getResponse().getContentAsString(), "$.accounts[0].id")).longValue();

        api.transfer(alice, demoWallet, 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ELIGIBLE"));
    }

    @Test
    void demoPageIsServed() throws Exception {
        api.getJsonAnonymous("/demo.html").andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions race(LockingMode mode, int transfers) throws Exception {
        return api.postJsonAnonymous("/api/demo/race", """
                {"mode": "%s", "transfers": %d}""".formatted(mode, transfers));
    }

    private static String join(List<Long> ids) {
        return ids.isEmpty() ? "-1" : String.join(",", ids.stream().map(String::valueOf).toList());
    }
}
