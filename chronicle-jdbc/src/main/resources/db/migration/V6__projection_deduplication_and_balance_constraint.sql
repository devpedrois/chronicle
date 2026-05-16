-- V6: Projection exactly-once deduplication + balance non-negative constraint

-- [SECURITY] Balance non-negative — DB-level defense-in-depth.
-- The aggregate enforces balance >= 0 before creating events, but injected or corrupted
-- events could push the read model negative without this constraint.
ALTER TABLE balances ADD CONSTRAINT chk_balance_non_negative CHECK (balance >= 0);

-- [SECURITY] Idempotency guard for projection event processing (fixes at-least-once re-delivery).
-- Stores (event_id, projection_name) after each successful event handle.
-- INSERT ON CONFLICT DO NOTHING is atomic — prevents double-credit/double-debit
-- when a JVM crash occurs after handle() committed but before position was saved.
CREATE TABLE IF NOT EXISTS processed_projection_events (
    event_id        UUID         NOT NULL,
    projection_name VARCHAR(255) NOT NULL,
    processed_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_processed_projection_events PRIMARY KEY (event_id, projection_name)
);
