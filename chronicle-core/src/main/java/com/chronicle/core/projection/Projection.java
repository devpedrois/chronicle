package com.chronicle.core.projection;

import com.chronicle.core.event.StoredEvent;

/**
 * Processes stored events to build or update a read model.
 * Implementations MUST be idempotent — processing the same event twice must not corrupt state.
 */
public interface Projection {

    /**
     * Processes a stored event and updates the read model.
     * Must be idempotent: applying the same event multiple times is safe.
     */
    void handle(StoredEvent event);

    /**
     * Resets the projection to its initial (empty) state.
     * Called when rebuilding the read model from scratch.
     */
    void reset();

    /**
     * Returns the unique name identifying this projection.
     * Used as primary key in the projection_positions table.
     */
    String getName();
}
