-- V5: Read model tables for projections
-- These tables are the CQRS read side — they are derived from events, not the source of truth.
-- They can be rebuilt at any time by replaying all events.

CREATE TABLE IF NOT EXISTS balances (
    account_id  UUID         PRIMARY KEY,
    owner_name  VARCHAR(255) NOT NULL,
    -- [SECURITY] Balance stored in cents as BIGINT — NEVER float/double to avoid rounding errors in financial calculations
    balance     BIGINT       NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
