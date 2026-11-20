-- =============================================================================
-- V2: idempotency keys
--
-- A client sends an Idempotency-Key header with each transfer or deposit. If the
-- network fails and the client retries with the same key, we must not move money
-- twice; we return the response we stored the first time instead.
--
-- The PRIMARY KEY is what makes this safe under concurrency: two requests with the
-- same key race to INSERT here, and PostgreSQL lets exactly one of them win. The
-- loser's INSERT waits until the winner's transaction finishes.
-- =============================================================================

CREATE TABLE idempotency_keys (
    idempotency_key  VARCHAR(100) PRIMARY KEY,
    -- SHA-256 (hex) of a canonical form of the request. Same key + different
    -- request hash = the client reused a key for a different operation -> 422.
    request_hash     VARCHAR(64)  NOT NULL,
    -- Filled in by the same transaction that moved the money, so a committed row
    -- always has them; they are only NULL inside that still-running transaction.
    transfer_id      BIGINT       REFERENCES transfers (id),
    response_body    TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
