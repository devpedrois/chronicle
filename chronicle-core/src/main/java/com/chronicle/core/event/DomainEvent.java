package com.chronicle.core.event;

/**
 * Marker interface for all domain events.
 * Implementations MUST be immutable records.
 * Events are never modified after creation — append-only by design.
 */
public interface DomainEvent {
}
