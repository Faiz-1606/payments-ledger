package com.example.ledger;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import com.example.ledger.support.AbstractIntegrationTest;

class OpenApiDocsTest extends AbstractIntegrationTest {

    @Test
    void openApiDocumentDescribesTheEndpoints() throws Exception {
        api.getJson("/v3/api-docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Payments Ledger API"))
                .andExpect(jsonPath("$.paths['/api/transfers']").exists())
                .andExpect(jsonPath("$.paths['/api/deposits']").exists())
                .andExpect(jsonPath("$.paths['/api/accounts/{accountId}']").exists());
    }

    @Test
    void swaggerUiIsServed() throws Exception {
        api.getJson("/swagger-ui/index.html").andExpect(status().isOk());
    }
}
