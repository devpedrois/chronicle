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
     * Returns the initial (empty) state before any events are applied.
     * Called once when creating a new {@link com.chronicle.core.aggregate.AggregateRoot}.
     *
     * @return the zero-value state
     */
    public abstract S initialState();

    /**
     * Applies a domain event to the current state, returning the new state.
     * This method MUST be a pure function — same inputs always produce the same output.
     * // apply() MUST be a pure function — no I/O, no mutation, no side effects
     *
     * @param state the current aggregate state
     * @param event the domain event to apply
     * @return the new aggregate state after applying the event
     */
    public abstract S apply(S state, DomainEvent event);

    /**
     * Returns the aggregate type name used for persistence.
     */
    public abstract String aggregateType();
}
