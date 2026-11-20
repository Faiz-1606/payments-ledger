-- =============================================================================
-- V3: authentication
--
-- Passwords are stored only as BCrypt hashes (60 characters, salt included).
-- Users created before this migration have no password and therefore cannot log
-- in; every user created from now on gets one.
-- =============================================================================

ALTER TABLE users ADD COLUMN password_hash VARCHAR(100);
ALTER TABLE users ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER';
ALTER TABLE users ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'));
