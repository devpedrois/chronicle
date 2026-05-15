-- V3: projection_positions table
-- Tracks the last processed event for each projection.
-- Enables projections to resume after restart without full replay.

CREATE TABLE IF NOT EXISTS projection_positions (
    projection_name VARCHAR(255) PRIMARY KEY,
    last_event_id   UUID         NOT NULL,
    last_version    BIGINT       NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
