package com.example.ledger.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests: the full Spring application against a real PostgreSQL.
 *
 * <p><b>One container for the whole test run.</b> The container is started once in a static
 * initializer and never stopped by us (Testcontainers' Ryuk sidecar removes it when the JVM
 * exits). Every test class that extends this base and uses the same configuration also
 * shares one cached Spring context, so the app boots once, not once per class.
 *
 * <p><b>Clean slate per test.</b> {@link DatabaseCleaner} empties the tables before each test,
 * so tests are independent and global invariants can be checked after every test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    static {
        POSTGRES.start();
    }

    /** Points Spring's DataSource at the container (its port is random). */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    protected TestApi api;
    protected LedgerInvariants invariants;

    @BeforeEach
    void resetDatabaseAndHelpers() {
        DatabaseCleaner.clean(jdbc);
        api = new TestApi(mockMvc);
        invariants = new LedgerInvariants(jdbc);
    }
}
