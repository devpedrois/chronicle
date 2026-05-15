package com.chronicle.core.snapshot;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of aggregate state at a given version.
 * Checksum is SHA-256 of the {@code state} JSON string.
 */
public record Snapshot(
        UUID aggregateId,
        String aggregateType,
        String state,
        int version,
        String checksum,
        Instant timestamp
) {
    public Snapshot {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(checksum, "checksum must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        // [SECURITY] Snapshot Integrity — checksum must be non-blank to detect empty/corrupt snapshots
        if (checksum.isBlank()) {
            throw new IllegalArgumentException("checksum must not be blank");
        }
        if (version < 1) {
            throw new IllegalArgumentException("snapshot version must be >= 1, got: " + version);
        }
    }
}
