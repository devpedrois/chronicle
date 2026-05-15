package com.chronicle.core.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable representation of a persisted domain event.
 * Once created, a StoredEvent cannot be modified — it is the canonical record of what happened.
 * Payload is a JSON string; deserialization is the caller's responsibility.
 */
public record StoredEvent(
        UUID eventId,
        UUID aggregateId,
        String aggregateType,
        String eventType,
        String payload,
        int version,
        Instant timestamp
) {
    public StoredEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");

        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1, got: " + version);
        }
        // [SECURITY] Payload size validated at record construction — defense in depth
        // Prevents DoS via oversized payloads propagating into the persistence layer
        if (payload.length() > 65536) {
            throw new IllegalArgumentException(
                    "payload exceeds 64KB limit: " + payload.length() + " chars");
        }
    }
}
