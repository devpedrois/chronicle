package com.chronicle.core.aggregate;

import com.chronicle.core.event.DomainEvent;

/**
 * Core abstraction for aggregate behavior.
 * The {@code apply} method MUST be a pure function: no side effects, no I/O, no mutation of external state.
 * It returns the new state given the current state and an event.
 *
 * @param <S> the aggregate state type (should be an immutable record or value object)
 */
public abstract class Aggregate<S> {

    /**
     * Applies a domain event to the current state, returning the new state.
     * This method MUST be a pure function — same inputs always produce the same output.
     *
     * @param state the current aggregate state (may be null for the initial event)
     * @param event the domain event to apply
     * @return the new aggregate state after applying the event
     */
    public abstract S apply(S state, DomainEvent event);

    /**
     * Returns the aggregate type name used for persistence.
     */
    public abstract String aggregateType();
}
