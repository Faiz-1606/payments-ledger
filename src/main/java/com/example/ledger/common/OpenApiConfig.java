package com.example.ledger.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadata for the generated OpenAPI document. springdoc scans the controllers itself;
 * this bean only adds a title and description. Browse it at /swagger-ui.html.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ledgerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Payments Ledger API")
                .version("v1")
                .description("""
                        INR wallets with a double-entry ledger. All amounts are integer paise \
                        (1 INR = 100 paise)."""));
    }
}
