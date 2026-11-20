-- =============================================================================
-- V4: transactional outbox (producer side) and processed-event inbox (consumer side)
--
-- Problem: after a transfer commits we want to publish "TransferCompleted" to Kafka.
-- Writing to the database and to Kafka are two separate systems, so no single
-- transaction covers both: commit-then-publish loses the event if we crash in between,
-- publish-then-commit announces transfers that may roll back.
--
-- Outbox pattern: the transfer transaction also INSERTs the event into outbox_events.
-- Both commit or neither does. A separate publisher reads unpublished rows, sends them
-- to Kafka, and marks them published. If Kafka is down, rows simply wait.
-- =============================================================================

CREATE TABLE outbox_events (
    id              UUID         PRIMARY KEY,       -- the event id; consumers dedupe on it
    aggregate_type  VARCHAR(50)  NOT NULL,          -- 'Transfer'
    aggregate_id    VARCHAR(50)  NOT NULL,          -- transfer id; also the Kafka message key
    event_type      VARCHAR(100) NOT NULL,          -- 'TransferCompleted'
    payload         TEXT         NOT NULL,          -- the event as JSON
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,                    -- NULL until Kafka acknowledged it
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      VARCHAR(500)
);

-- The publisher only ever looks for unpublished rows; a partial index stays tiny.
CREATE INDEX ix_outbox_events_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;

-- Consumer-side deduplication ("inbox"). Kafka delivers at least once, so the same
-- event can arrive twice; the PRIMARY KEY lets exactly one delivery be processed.
CREATE TABLE processed_events (
    event_id      UUID         PRIMARY KEY,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- What the consumer produces: one notification per user wallet involved in a transfer.
CREATE TABLE notifications (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id    UUID          NOT NULL,
    user_id     BIGINT        NOT NULL REFERENCES users (id),
    account_id  BIGINT        NOT NULL REFERENCES accounts (id),
    message     VARCHAR(300)  NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_notifications_event_account UNIQUE (event_id, account_id)
);

CREATE INDEX ix_notifications_user ON notifications (user_id, id DESC);
