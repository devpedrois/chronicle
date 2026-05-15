package com.chronicle.core.snapshot;

/**
 * Takes a snapshot once every {@code n} events since the last snapshot.
 * Stateless and side-effect free, as required by {@link SnapshotPolicy}.
 */
public final class EveryNEventsPolicy implements SnapshotPolicy {

    private final int n;

    /**
     * @param n minimum event delta before a snapshot is taken; must be >= 1
     */
    public EveryNEventsPolicy(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1, got: " + n);
        }
        this.n = n;
    }

    @Override
    public boolean shouldSnapshot(int currentVersion, int lastSnapshotVersion) {
        return (currentVersion - lastSnapshotVersion) >= n;
    }
}
