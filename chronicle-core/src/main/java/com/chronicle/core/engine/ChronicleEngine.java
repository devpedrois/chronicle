package com.chronicle.core.engine;

import com.chronicle.core.aggregate.Aggregate;
import com.chronicle.core.aggregate.AggregateRoot;
import com.chronicle.core.event.DomainEvent;
import com.chronicle.core.event.EventSerializer;
import com.chronicle.core.event.StoredEvent;
import com.chronicle.core.snapshot.Snapshot;
import com.chronicle.core.snapshot.SnapshotPolicy;
import com.chronicle.core.store.EventStore;
import com.chronicle.core.store.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chronicle.core.util.ChecksumUtil;

import java.util.Objects;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Façade that orchestrates loading (snapshot + replay) and saving (persist + auto-snapshot).
 * Version is always computed server-side — never accepted from external input.
 *
 * @param <S> the aggregate state type
 */
public class ChronicleEngine<S> {

    private static final Logger log = LoggerFactory.getLogger(ChronicleEngine.class);

    private final EventStore eventStore;
    private final SnapshotStore snapshotStore;
    private final EventSerializer<S> serializer;
    private final SnapshotPolicy snapshotPolicy;
    private final Aggregate<S> aggregate;
    private final Class<S> stateType;

    public ChronicleEngine(
            EventStore eventStore,
            SnapshotStore snapshotStore,
            EventSerializer<S> serializer,
            SnapshotPolicy snapshotPolicy,
            Aggregate<S> aggregate,
            Class<S> stateType) {
        // [SECURITY] Null checks at construction — NPE at use site would produce misleading errors
        // and could mask whether events were partially persisted before the failure
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore must not be null");
        this.serializer = Objects.requireNonNull(serializer, "serializer must not be null");
        this.snapshotPolicy = Objects.requireNonNull(snapshotPolicy, "snapshotPolicy must not be null");
        this.aggregate = Objects.requireNonNull(aggregate, "aggregate must not be null");
        this.stateType = Objects.requireNonNull(stateType, "stateType must not be null");
    }

    /**
     * Loads an aggregate: tries snapshot first, then replays remaining events.
     *
     * @param aggregateId the aggregate to load
     * @return populated AggregateRoot or empty if not found
     */
    public Optional<AggregateRoot<S>> load(UUID aggregateId) {
        // [SECURITY] Null aggregateId rejected — null would silently query all aggregates
        // matching NULL in PostgreSQL (none), returning Optional.empty() for any ID,
        // masking a caller bug and causing data loss if the caller then creates a duplicate.
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        AggregateRoot<S> root = new AggregateRoot<>(aggregate);
        root.setId(aggregateId);

        int startVersion = 0;

        Optional<Snapshot> snapshot = snapshotStore.loadLatest(aggregateId);
        if (snapshot.isPresent()) {
            Snapshot snap = snapshot.get();
            // [SECURITY] Secondary checksum validation — defense-in-depth against a buggy or malicious
            // SnapshotStore that returns a Snapshot without validating the checksum.
            // Formula must match the store: sha256(aggregateId + "|" + version + "|" + normalizedState).
            // Binding all three fields prevents state, version, and cross-aggregate injection.
            String checksumInput = snap.aggregateId() + "|" + snap.version() + "|" + snap.state();
            String recomputedChecksum = ChecksumUtil.sha256(checksumInput);
            if (recomputedChecksum.equals(snap.checksum())) {
                S state = serializer.deserializeState(snap.state(), stateClass());
                root.setState(state);
                root.setVersion(snap.version());
                root.setLastSnapshotVersion(snap.version());
                startVersion = snap.version();
                // [SECURITY] Snapshot validated → partial replay from snapshot.version + 1
            } else {
                // [SECURITY] Snapshot Integrity — secondary checksum mismatch triggers full replay
                // Store should have already caught this; this layer defends against a rogue store.
                log.warn("[SECURITY] Engine secondary checksum mismatch for aggregate {} at version {} — discarding, performing full replay",
                        aggregateId, snap.version());
            }
        }

        List<StoredEvent> events = startVersion == 0
                ? eventStore.load(aggregateId)
                : eventStore.loadAfterVersion(aggregateId, startVersion);

        if (events.isEmpty() && startVersion == 0) {
            return Optional.empty();
        }

        List<DomainEvent> domainEvents = events.stream()
                .map(e -> serializer.deserialize(e.payload(), e.eventType()))
                .toList();

        root.loadFromHistory(domainEvents);

        return Optional.of(root);
    }

    /**
     * Persists uncommitted events and takes a snapshot if the policy says so.
     * // [SECURITY] expectedVersion is computed server-side from the root's current state — never from client input
     *
     * @param root the aggregate root with uncommitted events
     */
    public void save(AggregateRoot<S> root) {
        // [SECURITY] Null root rejected early — NPE inside the method would produce a confusing
        // stack trace and could mask whether events were partially persisted before the failure.
        Objects.requireNonNull(root, "root must not be null");
        // [SECURITY] Null aggregateId on root rejected — a root without an ID cannot be persisted
        // reliably; all events would share a null aggregate_id, corrupting stream isolation.
        Objects.requireNonNull(root.getId(), "root.id must not be null — was the aggregate created correctly?");
        List<DomainEvent> uncommitted = root.getUncommittedEvents();
        if (uncommitted.isEmpty()) {
            return;
        }

        // [SECURITY] expectedVersion = server-side computation — version injection attack prevented
        int expectedVersion = root.getVersion() - uncommitted.size();

        List<StoredEvent> storedEvents = new ArrayList<>();
        int nextVersion = expectedVersion + 1;
        for (DomainEvent event : uncommitted) {
            storedEvents.add(new StoredEvent(
                    UUID.randomUUID(),
                    root.getId(),
                    aggregate.aggregateType(),
                    serializer.typeNameFor(event),
                    serializer.serialize(event),
                    nextVersion++,
                    Instant.now()
            ));
        }

        eventStore.save(root.getId(), aggregate.aggregateType(), storedEvents, expectedVersion);
        root.clearUncommittedEvents();

        // [SECURITY] Snapshot failure must NOT propagate to the caller — events are already committed.
        // A propagated exception would mislead the caller into believing the save failed,
        // causing a retry that produces ConcurrentModificationException on a successfully saved aggregate.
        try {
            // lastSnapshotVersion carried from load() — avoids an extra SELECT after every save().
            // On a cold root (never loaded via load()), this is 0, which correctly triggers the
            // first snapshot after N events.
            int lastSnapshotVersion = root.getLastSnapshotVersion();

            if (snapshotPolicy.shouldSnapshot(root.getVersion(), lastSnapshotVersion)) {
                String state = serializer.serializeState(root.getState());
                // [SECURITY] Checksum binds aggregateId + version + state — matches load() validation formula
                // sha256(state) alone would allow cross-aggregate and version-rollback attacks to pass the secondary check
                String checksumInput = root.getId() + "|" + root.getVersion() + "|" + state;
                String checksum = ChecksumUtil.sha256(checksumInput);
                Snapshot snap = new Snapshot(
                        root.getId(),
                        aggregate.aggregateType(),
                        state,
                        root.getVersion(),
                        checksum,
                        Instant.now()
                );
                snapshotStore.saveSnapshot(snap);
                root.setLastSnapshotVersion(root.getVersion());
            }
        } catch (Exception e) {
            log.warn("Snapshot failed for aggregate {} at version {} — events persisted, snapshot skipped: {}",
                    root.getId(), root.getVersion(), e.getMessage());
        }
    }

    private Class<S> stateClass() {
        return stateType;
    }
}
