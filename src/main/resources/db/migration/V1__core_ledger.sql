-- =============================================================================
-- V1: core ledger schema
--
-- Money is stored as BIGINT minor units (paise; 1 INR = 100 paise). Never use
-- floating point for money: 0.1 + 0.2 != 0.3 in binary floating point.
--
-- The database enforces every rule it can. Application code also checks these
-- rules, but constraints are the last line of defence when code has a bug.
-- =============================================================================

CREATE TABLE users (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    full_name   VARCHAR(100) NOT NULL,
    email       VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email)
);

-- kind = 'USER'    : a customer wallet, owned by a user, can never go below zero.
-- kind = 'FUNDING' : the single system account that deposits come from. Its balance
--                    is allowed to go negative; minus its balance = total money
--                    deposited into the system.
CREATE TABLE accounts (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id       BIGINT       REFERENCES users (id),
    kind           VARCHAR(16)  NOT NULL,
    currency       VARCHAR(3)   NOT NULL DEFAULT 'INR',
    balance_paise  BIGINT       NOT NULL DEFAULT 0,
    -- Incremented on every UPDATE by Hibernate (@Version); used for optimistic locking.
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_accounts_kind         CHECK (kind IN ('USER', 'FUNDING')),
    CONSTRAINT ck_accounts_currency     CHECK (currency = 'INR'),
    CONSTRAINT ck_accounts_owner        CHECK ((kind = 'FUNDING') = (owner_id IS NULL)),
    -- Invariant 2, enforced by the database itself.
    CONSTRAINT ck_accounts_non_negative CHECK (kind = 'FUNDING' OR balance_paise >= 0)
);

-- At most one funding account can exist.
CREATE UNIQUE INDEX uq_accounts_single_funding ON accounts (kind) WHERE kind = 'FUNDING';
CREATE INDEX ix_accounts_owner ON accounts (owner_id);

-- One row per money movement. Only completed transfers are stored: a failed transfer
-- rolls back its whole database transaction, so it leaves no trace.
CREATE TABLE transfers (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Filled in from Phase 2 (Idempotency-Key header). UNIQUE is a safety net: even a
    -- bug in the idempotency layer cannot create two transfers for one key.
    idempotency_key  VARCHAR(100),
    type             VARCHAR(16)  NOT NULL,
    from_account_id  BIGINT       NOT NULL REFERENCES accounts (id),
    to_account_id    BIGINT       NOT NULL REFERENCES accounts (id),
    amount_paise     BIGINT       NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_transfers_idempotency_key   UNIQUE (idempotency_key),
    CONSTRAINT ck_transfers_amount_positive   CHECK (amount_paise > 0),
    CONSTRAINT ck_transfers_distinct_accounts CHECK (from_account_id <> to_account_id),
    CONSTRAINT ck_transfers_type              CHECK (type IN ('DEPOSIT', 'TRANSFER')),
    CONSTRAINT ck_transfers_status            CHECK (status IN ('COMPLETED'))
);

CREATE INDEX ix_transfers_from ON transfers (from_account_id, id DESC);
CREATE INDEX ix_transfers_to   ON transfers (to_account_id, id DESC);

-- Double-entry ledger. Every transfer writes exactly two rows: -amount on the source
-- account and +amount on the destination account, so they sum to zero.
CREATE TABLE ledger_entries (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id   BIGINT       NOT NULL REFERENCES transfers (id),
    account_id    BIGINT       NOT NULL REFERENCES accounts (id),
    -- Signed: negative = money left the account, positive = money arrived.
    amount_paise  BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_ledger_entries_non_zero CHECK (amount_paise <> 0),
    -- from <> to, so a transfer has at most one entry per account (at most two in total).
    CONSTRAINT uq_ledger_entries_transfer_account UNIQUE (transfer_id, account_id)
);

-- "Recent entries for account X" reads this index backwards.
CREATE INDEX ix_ledger_entries_account ON ledger_entries (account_id, id DESC);

-- The ledger is append-only. Mistakes are fixed with new, compensating entries,
-- never by editing history. The database refuses UPDATE and DELETE outright.
CREATE FUNCTION forbid_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();

-- The system funding account. Deposits move money from here into user wallets.
INSERT INTO accounts (kind, currency, balance_paise) VALUES ('FUNDING', 'INR', 0);
