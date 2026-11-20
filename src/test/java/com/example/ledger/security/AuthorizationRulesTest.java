package com.example.ledger.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.example.ledger.support.AbstractIntegrationTest;
import com.example.ledger.support.TestApi;

/** One test per authorization rule. */
class AuthorizationRulesTest extends AbstractIntegrationTest {

    @Autowired
    JwtTokenService jwtTokenService;

    // ---- authentication ----------------------------------------------------------------

    @Test
    void protectedEndpointsRequireAToken() throws Exception {
        long alice = api.newAccountWithBalance(1_000);
        api.getJsonAnonymous("/api/accounts/" + alice)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        api.getJsonAnonymous("/api/transfers").andExpect(status().isUnauthorized());
        api.postMoney(null, "/api/transfers", """
                {"fromAccountId": %d, "toAccountId": 1, "amountPaise": 1}""".formatted(alice))
                .andExpect(status().isUnauthorized());
        assertThat(api.balance(alice)).isEqualTo(1_000);
    }

    @Test
    void garbageTokenIsRejected() throws Exception {
        api.getJsonAs("not.a.jwt", "/api/users/me")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        TestApi.TestUser user = api.registerUser();
        byte[] otherSecret = "a-completely-different-secret-0123456789".getBytes(StandardCharsets.UTF_8);
        NimbusJwtEncoder forger = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(otherSecret, "HmacSHA256")));
        String forged = forger.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer("payments-ledger").subject(Long.toString(user.id()))
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                        .claim("roles", List.of("ADMIN")).build())).getTokenValue();

        api.getJsonAs(forged, "/api/users/me").andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        TestApi.TestUser user = api.registerUser();
        String expired = jwtTokenService.issue(user.id(), "USER", Instant.now().minus(2, ChronoUnit.HOURS))
                .accessToken();
        api.getJsonAs(expired, "/api/users/me").andExpect(status().isUnauthorized());
    }

    @Test
    void publicEndpointsNeedNoToken() throws Exception {
        api.getJsonAnonymous("/v3/api-docs").andExpect(status().isOk());
        api.getJsonAnonymous("/swagger-ui/index.html").andExpect(status().isOk());
    }

    // ---- reading accounts ----------------------------------------------------------------

    @Test
    void userCanReadOwnAccount() throws Exception {
        long alice = api.newAccountWithBalance(1_000);
        api.getJsonAs(api.tokenForAccount(alice), "/api/accounts/" + alice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balancePaise").value(1_000));
    }

    @Test
    void userCannotReadSomeoneElsesAccount() throws Exception {
        long alice = api.newAccountWithBalance(1_000);
        long bob = api.newAccountWithBalance(0);
        api.getJsonAs(api.tokenForAccount(bob), "/api/accounts/" + alice)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void userCannotReadTheFundingAccount() throws Exception {
        long funding = jdbc.queryForObject("select id from accounts where kind = 'FUNDING'", Long.class);
        TestApi.TestUser user = api.registerUser();
        api.getJsonAs(user.token(), "/api/accounts/" + funding).andExpect(status().isForbidden());
    }

    @Test
    void adminCanReadAnyAccount() throws Exception {
        long alice = api.newAccountWithBalance(1_000);
        api.getJsonAs(api.adminToken(), "/api/accounts/" + alice).andExpect(status().isOk());
    }

    @Test
    void userCannotListSomeoneElsesAccounts() throws Exception {
        long alice = api.newAccountWithBalance(0);
        TestApi.TestUser mallory = api.registerUser();
        api.getJsonAs(mallory.token(), "/api/accounts?ownerId=" + api.ownerOf(alice))
                .andExpect(status().isForbidden());
    }

    // ---- moving money ----------------------------------------------------------------

    @Test
    void userCanTransferFromOwnAccountToAnyone() throws Exception {
        long alice = api.newAccountWithBalance(1_000);
        long bob = api.newAccountWithBalance(0);
        api.transferAs(api.tokenForAccount(alice), TestApi.newKey(), alice, bob, 300)
                .andExpect(status().isCreated());
        assertThat(api.balance(bob)).isEqualTo(300);
    }

    @Test
    void userCannotMoveMoneyOutOfSomeoneElsesAccount() throws Exception {
        long alice = api.newAccountWithBalance(1_000);
        long mallory = api.newAccountWithBalance(0);
        api.transferAs(api.tokenForAccount(mallory), TestApi.newKey(), alice, mallory, 1_000)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(api.balance(alice)).isEqualTo(1_000);
        assertThat(api.balance(mallory)).isZero();
    }

    @Test
    void adminCannotMoveMoneyOutOfAUsersAccount() throws Exception {
        long alice = api.newAccountWithBalance(1_000);
        long bob = api.newAccountWithBalance(0);
        api.transferAs(api.adminToken(), TestApi.newKey(), alice, bob, 100).andExpect(status().isForbidden());
        assertThat(api.balance(alice)).isEqualTo(1_000);
    }

    @Test
    void userCannotDeposit() throws Exception {
        long alice = api.newAccountWithBalance(0);
        api.postMoney(api.tokenForAccount(alice), "/api/deposits", """
                        {"accountId": %d, "amountPaise": 1000000}""".formatted(alice))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(api.balance(alice)).isZero();
    }

    @Test
    void adminCanDeposit() throws Exception {
        long alice = api.newAccountWithBalance(0);
        api.deposit(alice, 5_000).andExpect(status().isCreated());
        assertThat(api.balance(alice)).isEqualTo(5_000);
    }

    @Test
    void anotherUserReusingYourIdempotencyKeyGets422NotYourResponse() throws Exception {
        long alice = api.newAccountWithBalance(1_000);
        long bob = api.newAccountWithBalance(1_000);
        String key = TestApi.newKey();
        api.transferAs(api.tokenForAccount(alice), key, alice, bob, 100).andExpect(status().isCreated());

        api.transferAs(api.tokenForAccount(bob), key, bob, alice, 100)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    // ---- reading transfers -------------------------------------------------------------

    @Test
    void userListsOnlyTransfersTouchingTheirAccounts() throws Exception {
        long alice = api.newAccountWithBalance(1_000);
        long bob = api.newAccountWithBalance(1_000);
        long carol = api.newAccountWithBalance(1_000);
        api.transfer(alice, bob, 10).andExpect(status().isCreated());
        api.transfer(bob, carol, 20).andExpect(status().isCreated());

        // Carol sees her deposit and bob->carol, not alice->bob.
        api.getJsonAs(api.tokenForAccount(carol), "/api/transfers")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2));
        api.getJsonAs(api.tokenForAccount(carol), "/api/transfers?accountId=" + alice)
                .andExpect(status().isForbidden());
        // Admin sees all 5 (3 deposits + 2 transfers).
        api.getJson("/api/transfers").andExpect(jsonPath("$.totalItems").value(5));
    }

    @Test
    void transferDetailsVisibleOnlyToInvolvedUsers() throws Exception {
        long alice = api.newAccountWithBalance(1_000);
        long bob = api.newAccountWithBalance(0);
        long carol = api.newAccountWithBalance(0);
        long id = TestApi.readLong(api.transfer(alice, bob, 10).andReturn(), "$.id");

        api.getJsonAs(api.tokenForAccount(alice), "/api/transfers/" + id).andExpect(status().isOk());
        api.getJsonAs(api.tokenForAccount(bob), "/api/transfers/" + id).andExpect(status().isOk());
        api.getJsonAs(api.tokenForAccount(carol), "/api/transfers/" + id).andExpect(status().isForbidden());
    }

    // ---- admin-only endpoints ------------------------------------------------------------

    @Test
    void userEndpointsByIdAreAdminOnly() throws Exception {
        TestApi.TestUser user = api.registerUser();
        api.getJsonAs(user.token(), "/api/users/" + user.id()).andExpect(status().isForbidden());
        api.getJson("/api/users/" + user.id()).andExpect(status().isOk());
    }
}
