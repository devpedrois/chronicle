package com.chronicle.core.projection;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Tracks the last processed event position for a projection.
 * Persisted so projections can resume after restart without full replay.
 */
public record ProjectionPosition(
        String projectionName,
        UUID lastEventId,
        long lastVersion,
        Instant updatedAt
) {
    public ProjectionPosition {
        Objects.requireNonNull(projectionName, "projectionName must not be null");
        Objects.requireNonNull(lastEventId, "lastEventId must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (lastVersion < 0) {
            throw new IllegalArgumentException("lastVersion must be >= 0, got: " + lastVersion);
        }
    }
}
