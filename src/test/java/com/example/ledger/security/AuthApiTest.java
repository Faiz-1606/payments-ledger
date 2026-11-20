package com.example.ledger.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.example.ledger.support.AbstractIntegrationTest;
import com.example.ledger.support.TestApi;

class AuthApiTest extends AbstractIntegrationTest {

    @Test
    void registerCreatesUserWithNormalizedEmailAndUserRole() throws Exception {
        api.postJsonAnonymous("/api/auth/register", """
                        {"fullName": " Asha Rao ", "email": "Asha.Rao@Example.com", "password": "correct-horse"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.fullName").value("Asha Rao"))
                .andExpect(jsonPath("$.email").value("asha.rao@example.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void passwordIsStoredAsBcryptHashNotPlaintext() throws Exception {
        TestApi.TestUser user = api.registerUser();
        String stored = jdbc.queryForObject("select password_hash from users where id = ?", String.class, user.id());
        assertThat(stored).startsWith("$2").hasSize(60).doesNotContain(user.password());
    }

    @Test
    void duplicateEmailIsConflict() throws Exception {
        TestApi.TestUser user = api.registerUser();
        api.postJsonAnonymous("/api/auth/register", """
                        {"fullName": "B", "email": "%s", "password": "another-password"}""".formatted(user.email().toUpperCase()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void invalidRegistrationListsFieldErrors() throws Exception {
        api.postJsonAnonymous("/api/auth/register", """
                        {"fullName": "", "email": "not-an-email", "password": "short"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("email")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("fullName")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("password")));
    }

    @Test
    void loginReturnsBearerToken() throws Exception {
        TestApi.TestUser user = api.registerUser();
        api.postJsonAnonymous("/api/auth/login", """
                        {"email": "%s", "password": "%s"}""".formatted(user.email(), user.password()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(3600))
                .andExpect(jsonPath("$.accessToken").isString());
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSame401() throws Exception {
        TestApi.TestUser user = api.registerUser();
        String wrongPassword = api.postJsonAnonymous("/api/auth/login", """
                        {"email": "%s", "password": "not-the-password"}""".formatted(user.email()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = api.postJsonAnonymous("/api/auth/login", """
                        {"email": "nobody@example.com", "password": "whatever-123"}""")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        assertThat(withoutRequestId(unknownEmail)).as("no user enumeration via the error body")
                .isEqualTo(withoutRequestId(wrongPassword));
    }

    /** Every response has its own requestId; everything else must be identical. */
    private static String withoutRequestId(String json) {
        return json.replaceAll("\"requestId\":\"[^\"]*\"", "");
    }

    @Test
    void meReturnsTheCallersProfile() throws Exception {
        TestApi.TestUser user = api.registerUser();
        api.getJsonAs(user.token(), "/api/users/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.id()))
                .andExpect(jsonPath("$.email").value(user.email()));
    }

    @Test
    void bootstrapAdminCanLogIn() throws Exception {
        api.postJsonAnonymous("/api/auth/login", """
                        {"email": "admin@ledger.test", "password": "admin-password-for-tests"}""")
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select role from users where email = 'admin@ledger.test'", String.class))
                .isEqualTo("ADMIN");
    }
}
