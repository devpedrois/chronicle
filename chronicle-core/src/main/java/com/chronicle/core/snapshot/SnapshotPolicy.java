package com.chronicle.core.snapshot;

/**
 * Determines whether a new snapshot should be taken.
 * Implementations must be stateless and side-effect free.
 */
@FunctionalInterface
public interface SnapshotPolicy {

    /**
     * @param currentVersion      current version of the aggregate
     * @param lastSnapshotVersion version at which the last snapshot was taken (0 if none)
     * @return true if a new snapshot should be persisted
     */
    boolean shouldSnapshot(int currentVersion, int lastSnapshotVersion);
}
