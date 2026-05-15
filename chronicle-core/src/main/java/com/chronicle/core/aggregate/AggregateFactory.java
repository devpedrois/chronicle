package com.chronicle.core.aggregate;

import java.util.function.Supplier;

/**
 * Factory for creating new {@link AggregateRoot} instances.
 * Using a functional interface keeps aggregate creation testable and composable.
 *
 * @param <S> the aggregate state type
 */
@FunctionalInterface
public interface AggregateFactory<S> extends Supplier<AggregateRoot<S>> {
}
