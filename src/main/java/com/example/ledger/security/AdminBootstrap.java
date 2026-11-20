package com.example.ledger.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.example.ledger.user.EmailAlreadyRegisteredException;
import com.example.ledger.user.Role;
import com.example.ledger.user.UserService;

/**
 * Creates the first ADMIN user at startup from {@code ledger.security.admin-email} and
 * {@code admin-password} (ADMIN_EMAIL / ADMIN_PASSWORD environment variables). There is no
 * API to grant ADMIN, so this is the only way an admin can exist.
 *
 * <p>{@link ApplicationRunner} beans run once the application context is fully started.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final SecurityProperties properties;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrap(SecurityProperties properties, UserService userService, PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureAdminExists();
    }

    /** Idempotent: does nothing if the admin already exists or no admin is configured. */
    public void ensureAdminExists() {
        if (!properties.adminBootstrapConfigured()) {
            log.info("No bootstrap admin configured (ledger.security.admin-email is empty)");
            return;
        }
        if (userService.findByEmail(properties.adminEmail()).isPresent()) {
            return;
        }
        try {
            userService.createUser("Administrator", properties.adminEmail(),
                    passwordEncoder.encode(properties.adminPassword()), Role.ADMIN);
            log.info("Created bootstrap admin {}", properties.adminEmail());
        } catch (EmailAlreadyRegisteredException anotherInstanceWon) {
            // Two instances starting at once: one created it, which is all we wanted.
        }
    }
}
