package com.chronicle.core.store;

import com.chronicle.core.event.StoredEvent;

import java.util.List;
import java.util.UUID;

/**
 * Append-only store for domain events.
 *
 * <p>Contracts:
 * <ul>
 *   <li>Events are persisted in version order within an aggregate stream.</li>
 *   <li>Once saved, events are immutable — no update or delete is possible.</li>
 *   <li>Optimistic locking is enforced via {@code expectedVersion}.</li>
 * </ul>
 *
 * <p>Implementations must guarantee atomicity: all events in a single {@code save()} call
 * are either all persisted or none.
 */
public interface EventStore {

    // [SECURITY] No delete() or update() methods — events are append-only and immutable by design
    // Removing these methods at the interface level makes accidental mutation impossible in any layer

    /**
     * Appends events to the aggregate stream.
     *
     * @param aggregateId   the aggregate identifier
     * @param aggregateType the aggregate type name
     * @param events        ordered list of events to persist (version must be sequential)
     * @param expectedVersion the version the aggregate was at before these events
     * @throws ConcurrentModificationException if another writer has already appended events
     */
    void save(UUID aggregateId, String aggregateType, List<StoredEvent> events, int expectedVersion);

    /**
     * Loads all events for an aggregate, ordered by version ascending.
     *
     * @param aggregateId the aggregate identifier
     * @return ordered list of stored events; empty list if aggregate does not exist
     */
    List<StoredEvent> load(UUID aggregateId);

    /**
     * Loads events after a specific version (exclusive), ordered by version ascending.
     * Used for incremental replay from a snapshot.
     *
     * @param aggregateId  the aggregate identifier
     * @param afterVersion events with version strictly greater than this are returned
     * @return ordered list of stored events after the given version
     */
    List<StoredEvent> loadAfterVersion(UUID aggregateId, int afterVersion);
}
