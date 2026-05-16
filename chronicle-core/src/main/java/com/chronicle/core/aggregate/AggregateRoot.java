package com.chronicle.core.aggregate;

import com.chronicle.core.event.DomainEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
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
    private int lastSnapshotVersion;
    private final List<DomainEvent> uncommittedEvents = new ArrayList<>();
    private final Aggregate<S> aggregate;

    public AggregateRoot(Aggregate<S> aggregate) {
        this.aggregate = aggregate;
        this.state = aggregate.initialState();
    }

    /**
     * Replays historical events to reconstruct state.
     * Does NOT add events to uncommittedEvents.
     */
    public void loadFromHistory(List<DomainEvent> history) {
        // [SECURITY] Null check — null history or null elements would corrupt version counter silently
        Objects.requireNonNull(history, "history must not be null");
        for (DomainEvent event : history) {
            Objects.requireNonNull(event, "history must not contain null events");
            state = aggregate.apply(state, event);
            version++;
        }
    }

    /**
     * Applies a new event, adds it to uncommitted list, and increments version.
     * Called by command methods on the aggregate to record what happened.
     */
    public void handleEvent(DomainEvent event) {
        // [SECURITY] Null check — null event would silently increment version without applying state change,
        // causing a version/state desync that is impossible to detect or recover from
        Objects.requireNonNull(event, "event must not be null");
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
        // [SECURITY] Null id rejected — null would corrupt event stream isolation (all events share null aggregate_id)
        Objects.requireNonNull(id, "id must not be null");
        this.id = id;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        // [SECURITY] Negative version rejected — negative version corrupts expectedVersion computation in save()
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0, got: " + version);
        }
        this.version = version;
    }

    public S getState() {
        return state;
    }

    public void setState(S state) {
        // [SECURITY] Null state rejected — null state bypasses apply() pure-function invariant
        Objects.requireNonNull(state, "state must not be null");
        this.state = state;
    }

    public int getLastSnapshotVersion() {
        return lastSnapshotVersion;
    }

    public void setLastSnapshotVersion(int lastSnapshotVersion) {
        if (lastSnapshotVersion < 0) {
            throw new IllegalArgumentException("lastSnapshotVersion must be >= 0, got: " + lastSnapshotVersion);
        }
        this.lastSnapshotVersion = lastSnapshotVersion;
    }
}
