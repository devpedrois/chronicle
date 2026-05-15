package com.chronicle.core.snapshot;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of aggregate state at a given version.
 *
 * <p>Checksum is SHA-256 of {@code aggregateId + "|" + version + "|" + normalizedState},
 * binding the checksum to all three fields so that tampering with any one of them
 * (state, version, or aggregateId) is detected on load.
 */
public record Snapshot(
        UUID aggregateId,
        String aggregateType,
        String state,
        int version,
        String checksum,
        Instant timestamp
) {
    // [SECURITY] Snapshot state size limit — prevents OOM during deserialization if a compromised DB
    // contains a maliciously large state blob. 256 KB bytes is generous for any realistic aggregate state.
    public static final int MAX_STATE_SIZE = 262_144; // bytes

    public Snapshot {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(checksum, "checksum must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        // [SECURITY] Blank aggregateType rejected — blank type breaks aggregate stream isolation
        if (aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType must not be blank");
        }
        // [SECURITY] Snapshot state size limit — defense against OOM via oversized state blob
        // Byte count checked (not char count) — multi-byte UTF-8 chars would exceed the DB limit
        byte[] stateBytes = state.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (stateBytes.length > MAX_STATE_SIZE) {
            throw new IllegalArgumentException(
                    "snapshot state exceeds 256 KB limit: " + stateBytes.length + " bytes");
        }
        // [SECURITY] Snapshot Integrity — checksum must be valid SHA-256 hex (64 lowercase hex chars)
        if (checksum.isBlank()) {
            throw new IllegalArgumentException("checksum must not be blank");
        }
        if (checksum.length() != 64 || !checksum.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "checksum must be a valid SHA-256 hex string (64 lowercase hex chars), got length: " + checksum.length());
        }
        if (version < 1) {
            throw new IllegalArgumentException("snapshot version must be >= 1, got: " + version);
        }
    }
}
