-- V1: events table — APPEND-ONLY, IMMUTABLE
-- This table is the canonical record of everything that has ever happened in the system.
-- Rows are NEVER updated or deleted — only INSERTs are permitted.

CREATE TABLE IF NOT EXISTS events (
    event_id        UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id    UUID         NOT NULL,
    aggregate_type  VARCHAR(255) NOT NULL,
    event_type      VARCHAR(255) NOT NULL,
    payload         JSONB        NOT NULL,
    version         INT          NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- [SECURITY] Optimistic locking constraint — prevents concurrent writers from producing duplicate versions
    -- A DataIntegrityViolationException on this constraint signals a ConcurrentModificationException
    CONSTRAINT uq_events_aggregate_version UNIQUE (aggregate_id, version)
);

-- Primary access pattern: load all events for an aggregate in version order
CREATE INDEX IF NOT EXISTS idx_events_aggregate
    ON events (aggregate_id, version ASC);

-- Secondary index: time-range queries and audit queries
CREATE INDEX IF NOT EXISTS idx_events_created
    ON events (created_at);

-- Secondary index: filtering by event type for projections
CREATE INDEX IF NOT EXISTS idx_events_type
    ON events (event_type);
