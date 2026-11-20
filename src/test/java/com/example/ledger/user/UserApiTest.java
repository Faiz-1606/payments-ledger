package com.example.ledger.user;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.example.ledger.support.AbstractIntegrationTest;

class UserApiTest extends AbstractIntegrationTest {

    @Test
    void createsUserAndNormalizesEmail() throws Exception {
        api.postJson("/api/users", """
                        {"fullName": " Asha Rao ", "email": "Asha.Rao@Example.com"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.fullName").value("Asha Rao"))
                .andExpect(jsonPath("$.email").value("asha.rao@example.com"));
    }

    @Test
    void duplicateEmailIsConflictProblem() throws Exception {
        api.postJson("/api/users", """
                {"fullName": "A", "email": "dup@example.com"}""").andExpect(status().isCreated());

        api.postJson("/api/users", """
                        {"fullName": "B", "email": "DUP@example.com"}""")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.type").value("/problems/email-already-registered"));
    }

    @Test
    void invalidEmailIsValidationProblemWithFieldErrors() throws Exception {
        api.postJson("/api/users", """
                        {"fullName": "", "email": "not-an-email"}""")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("email")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("fullName")));
    }

    @Test
    void unknownUserIsNotFound() throws Exception {
        api.getJson("/api/users/999999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }
}
