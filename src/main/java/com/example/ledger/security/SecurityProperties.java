package com.example.ledger.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code ledger.security.*} settings.
 *
 * @param jwtSecret      HMAC-SHA256 signing key; at least 32 bytes. Set JWT_SECRET in production.
 * @param tokenTtl       lifetime of an access token
 * @param issuer         "iss" claim written into and required from every token
 * @param bcryptStrength BCrypt cost factor (2^strength rounds); 10 in production, lower in tests
 * @param adminEmail     if set (with adminPassword), an ADMIN user is created at startup
 * @param adminPassword  password for that bootstrap admin
 */
@ConfigurationProperties(prefix = "ledger.security")
public record SecurityProperties(
        String jwtSecret,
        @DefaultValue("1h") Duration tokenTtl,
        @DefaultValue("payments-ledger") String issuer,
        @DefaultValue("10") int bcryptStrength,
        String adminEmail,
        String adminPassword) {

    public SecurityProperties {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(
                    "ledger.security.jwt-secret must be at least 32 bytes (HS256 needs a 256-bit key)");
        }
    }

    public boolean adminBootstrapConfigured() {
        return adminEmail != null && !adminEmail.isBlank() && adminPassword != null && !adminPassword.isBlank();
    }
}
