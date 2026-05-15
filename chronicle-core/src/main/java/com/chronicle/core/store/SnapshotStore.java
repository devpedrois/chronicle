package com.chronicle.core.store;

import com.chronicle.core.snapshot.Snapshot;

import java.util.Optional;
import java.util.UUID;

/**
 * Store for aggregate snapshots.
 * Only the latest snapshot per aggregate is retained.
 */
public interface SnapshotStore {

    /**
     * Persists or replaces the snapshot for an aggregate.
     * UPSERT semantics: one snapshot per aggregate at a time.
     */
    void saveSnapshot(Snapshot snapshot);

    /**
     * Returns the latest snapshot for an aggregate, if one exists.
     *
     * @param aggregateId the aggregate identifier
     * @return snapshot or empty if none saved
     */
    Optional<Snapshot> loadLatest(UUID aggregateId);
}
