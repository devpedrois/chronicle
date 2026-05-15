-- V2: snapshots table
-- Stores the latest serialized aggregate state to speed up replay.
-- One snapshot per aggregate (UPSERT semantics enforced via UNIQUE constraint).

CREATE TABLE IF NOT EXISTS snapshots (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id    UUID         NOT NULL,
    aggregate_type  VARCHAR(255) NOT NULL,
    state           JSONB        NOT NULL,
    version         INT          NOT NULL,
    -- [SECURITY] Snapshot Integrity — SHA-256 checksum detects tampering or corruption
    -- On read, checksum is recomputed and compared; mismatch triggers full replay
    checksum        VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_snapshots_aggregate UNIQUE (aggregate_id)
);
