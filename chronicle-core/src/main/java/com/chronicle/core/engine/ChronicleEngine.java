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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
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

    public ChronicleEngine(
            EventStore eventStore,
            SnapshotStore snapshotStore,
            EventSerializer<S> serializer,
            SnapshotPolicy snapshotPolicy,
            Aggregate<S> aggregate) {
        this.eventStore = eventStore;
        this.snapshotStore = snapshotStore;
        this.serializer = serializer;
        this.snapshotPolicy = snapshotPolicy;
        this.aggregate = aggregate;
    }

    /**
     * Loads an aggregate: tries snapshot first, then replays remaining events.
     *
     * @param aggregateId the aggregate to load
     * @return populated AggregateRoot or empty if not found
     */
    public Optional<AggregateRoot<S>> load(UUID aggregateId) {
        AggregateRoot<S> root = new AggregateRoot<>(aggregate);
        root.setId(aggregateId);

        int startVersion = 0;

        Optional<Snapshot> snapshot = snapshotStore.loadLatest(aggregateId);
        if (snapshot.isPresent()) {
            Snapshot snap = snapshot.get();
            String recomputedChecksum = sha256(snap.state());
            if (recomputedChecksum.equals(snap.checksum())) {
                S state = serializer.deserializeState(snap.state(), stateClass());
                root.setState(state);
                root.setVersion(snap.version());
                startVersion = snap.version();
            } else {
                // [SECURITY] Snapshot Integrity — checksum mismatch triggers full replay
                // Tampered or corrupted snapshot is silently discarded; system remains consistent via replay
                log.warn("[SECURITY] Snapshot checksum mismatch for aggregate {} at version {} — discarding, performing full replay",
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
                    event.getClass().getSimpleName(),
                    serializer.serialize(event),
                    nextVersion++,
                    Instant.now()
            ));
        }

        eventStore.save(root.getId(), aggregate.aggregateType(), storedEvents, expectedVersion);
        root.clearUncommittedEvents();

        int lastSnapshotVersion = snapshotStore.loadLatest(root.getId())
                .map(Snapshot::version)
                .orElse(0);

        if (snapshotPolicy.shouldSnapshot(root.getVersion(), lastSnapshotVersion)) {
            String state = serializer.serializeState(root.getState());
            String checksum = sha256(state);
            Snapshot snap = new Snapshot(
                    root.getId(),
                    aggregate.aggregateType(),
                    state,
                    root.getVersion(),
                    checksum,
                    Instant.now()
            );
            snapshotStore.saveSnapshot(snap);
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Class<S> stateClass() {
        return (Class<S>) Object.class;
    }
}
