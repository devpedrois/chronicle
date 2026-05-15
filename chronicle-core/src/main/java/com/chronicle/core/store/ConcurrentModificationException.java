package com.chronicle.core.store;

import java.util.UUID;

/**
 * Thrown when optimistic locking detects a concurrent write conflict.
 * The caller should retry the command after reloading the aggregate.
 */
public class ConcurrentModificationException extends RuntimeException {

    private final UUID aggregateId;
    private final int expectedVersion;
    private final int actualVersion;

    public ConcurrentModificationException(UUID aggregateId, int expectedVersion, int actualVersion) {
        super(String.format(
                "Concurrent modification on aggregate %s: expected version %d but found %d",
                aggregateId, expectedVersion, actualVersion));
        this.aggregateId = aggregateId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public int getExpectedVersion() {
        return expectedVersion;
    }

    public int getActualVersion() {
        return actualVersion;
    }
}
