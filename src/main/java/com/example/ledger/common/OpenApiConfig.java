package com.example.ledger.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadata for the generated OpenAPI document. springdoc scans the controllers itself;
 * this bean adds a title, a description and the bearer-token scheme, so Swagger UI shows an
 * "Authorize" button (paste the accessToken from /api/auth/login). Browse it at /swagger-ui.html.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ledgerOpenApi() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .info(new Info()
                        .title("Payments Ledger API")
                        .version("v1")
                        .description("""
                                INR wallets with a double-entry ledger. All amounts are integer paise \
                                (1 INR = 100 paise)."""));
    }
}
