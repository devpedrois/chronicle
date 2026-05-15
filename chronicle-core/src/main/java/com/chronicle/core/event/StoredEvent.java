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

        // [SECURITY] Blank strings rejected — empty aggregateType/eventType corrupt event streams
        // and break EventTypeRegistry lookups, potentially causing silent deserialization failures
        if (aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType must not be blank");
        }
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        // [SECURITY] Field length validated against DB schema (VARCHAR(255)) — fail-fast at app layer
        // prevents raw DB constraint errors from propagating up with internal details
        if (aggregateType.length() > 255) {
            throw new IllegalArgumentException(
                    "aggregateType exceeds 255 chars: " + aggregateType.length());
        }
        if (eventType.length() > 255) {
            throw new IllegalArgumentException(
                    "eventType exceeds 255 chars: " + eventType.length());
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1, got: " + version);
        }
        // [SECURITY] Payload size validated at record construction — defense in depth
        // Byte count checked (not char count) — multi-byte UTF-8 chars would exceed the DB limit
        byte[] payloadBytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (payloadBytes.length > 65536) {
            throw new IllegalArgumentException(
                    "payload exceeds 64KB limit: " + payloadBytes.length + " bytes");
        }
    }
}
