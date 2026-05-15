package com.chronicle.core.aggregate;

import com.chronicle.core.event.DomainEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Container for aggregate identity, version, state, and uncommitted events.
 * Manages event application and tracks events pending persistence.
 *
 * @param <S> the aggregate state type
 */
public class AggregateRoot<S> {

    private UUID id;
    private int version;
    private S state;
    private final List<DomainEvent> uncommittedEvents = new ArrayList<>();
    private final Aggregate<S> aggregate;

    public AggregateRoot(Aggregate<S> aggregate) {
        this.aggregate = aggregate;
    }

    /**
     * Replays historical events to reconstruct state.
     * Does NOT add events to uncommittedEvents.
     */
    public void loadFromHistory(List<DomainEvent> history) {
        for (DomainEvent event : history) {
            state = aggregate.apply(state, event);
            version++;
        }
    }

    /**
     * Applies a new event, adds it to uncommitted list, and increments version.
     * Called by command methods on the aggregate to record what happened.
     */
    public void handleEvent(DomainEvent event) {
        state = aggregate.apply(state, event);
        uncommittedEvents.add(event);
        version++;
    }

    /**
     * Returns an unmodifiable view of events not yet persisted.
     * // [SECURITY] Unmodifiable list prevents external mutation of pending events
     */
    public List<DomainEvent> getUncommittedEvents() {
        return Collections.unmodifiableList(uncommittedEvents);
    }

    public void clearUncommittedEvents() {
        uncommittedEvents.clear();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public S getState() {
        return state;
    }

    public void setState(S state) {
        this.state = state;
    }
}
