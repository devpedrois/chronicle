-- V7: Add created_at to balances read model
-- [SECURITY] created_at tracks when the account projection was first created.
-- DEFAULT NOW() fills existing rows safely during migration.
ALTER TABLE balances
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
