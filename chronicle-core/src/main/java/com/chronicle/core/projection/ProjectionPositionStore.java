package com.chronicle.core.projection;

import java.util.Optional;

/**
 * Persists and retrieves projection checkpoint positions.
 */
public interface ProjectionPositionStore {

    /**
     * Returns the last saved position for a projection.
     *
     * @param projectionName the projection identifier
     * @return position or empty if no checkpoint exists yet
     */
    Optional<ProjectionPosition> getPosition(String projectionName);

    /**
     * Saves or updates the position checkpoint for a projection.
     * UPSERT semantics.
     */
    void savePosition(ProjectionPosition position);

    /**
     * Deletes the position checkpoint for a projection.
     * Called when resetting a projection to rebuild the read model from scratch.
     *
     * @param projectionName the projection identifier
     */
    void deletePosition(String projectionName);
}
