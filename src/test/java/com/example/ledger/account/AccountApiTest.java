package com.example.ledger.account;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import com.example.ledger.support.AbstractIntegrationTest;
import com.example.ledger.support.TestApi;

class AccountApiTest extends AbstractIntegrationTest {

    @Test
    void opensEmptyInrWalletOwnedByTheCaller() throws Exception {
        TestApi.TestUser user = api.registerUser();

        api.postJsonAs(user.token(), "/api/accounts", "")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(user.id()))
                .andExpect(jsonPath("$.kind").value("USER"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.balancePaise").value(0));
    }

    @Test
    void openingAccountRequiresAuthentication() throws Exception {
        api.postJsonAnonymous("/api/accounts", "")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void getAccountShowsBalanceAndRecentEntriesNewestFirst() throws Exception {
        long alice = api.newAccountWithBalance(10_000);
        long bob = api.newAccountWithBalance(0);
        api.transfer(alice, bob, 2_500).andExpect(status().isCreated());

        api.getJson("/api/accounts/" + alice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balancePaise").value(7_500))
                .andExpect(jsonPath("$.recentEntries.length()").value(2))
                .andExpect(jsonPath("$.recentEntries[0].amountPaise").value(-2_500)) // newest: the transfer out
                .andExpect(jsonPath("$.recentEntries[1].amountPaise").value(10_000)); // older: the deposit
    }

    @Test
    void unknownAccountIsNotFound() throws Exception {
        api.getJson("/api/accounts/987654")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void nonPositiveAccountIdIsBadRequest() throws Exception {
        api.getJson("/api/accounts/0").andExpect(status().isBadRequest());
    }

    @Test
    void listsTheCallersOwnAccounts() throws Exception {
        long userId = api.createUser();
        api.openAccount(userId);
        api.openAccount(userId);
        api.newAccountWithBalance(0); // someone else's

        api.getJsonAs(api.tokenOf(userId), "/api/accounts")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        api.getJson("/api/accounts?ownerId=" + userId) // admin
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }
}
