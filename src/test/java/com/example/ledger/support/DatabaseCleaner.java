package com.example.ledger.support;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Resets the database between tests while keeping the schema and the funding account that the
 * V1 migration created.
 *
 * <p>TRUNCATE is used for the ledger because the append-only trigger blocks DELETE on
 * ledger_entries; row-level triggers do not fire on TRUNCATE.
 */
public final class DatabaseCleaner {

    private static final List<String> KEEP = List.of("flyway_schema_history", "accounts", "users");

    private DatabaseCleaner() {
    }

    public static void clean(JdbcTemplate jdbc) {
        List<String> tables = jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'public' and table_type = 'BASE TABLE'""", String.class);
        List<String> toTruncate = tables.stream().filter(t -> !KEEP.contains(t)).toList();
        if (!toTruncate.isEmpty()) {
            jdbc.execute("TRUNCATE TABLE " + String.join(", ", toTruncate) + " RESTART IDENTITY CASCADE");
        }
        jdbc.update("DELETE FROM accounts WHERE kind <> 'FUNDING'");
        jdbc.update("UPDATE accounts SET balance_paise = 0, version = 0 WHERE kind = 'FUNDING'");
        jdbc.update("DELETE FROM users");
    }
}
