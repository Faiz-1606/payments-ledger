-- =============================================================================
-- V5: a third account kind, DEMO, used only by the race demo (demo profile).
--
-- Demo accounts are ordinary wallets in every respect (non-negative, owned, double
-- entry), but transfers can only move money DEMO -> DEMO, so the deliberately broken
-- naive mode in the demo can never touch a real user's wallet.
-- =============================================================================

ALTER TABLE accounts DROP CONSTRAINT ck_accounts_kind;
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_kind CHECK (kind IN ('USER', 'FUNDING', 'DEMO'));
