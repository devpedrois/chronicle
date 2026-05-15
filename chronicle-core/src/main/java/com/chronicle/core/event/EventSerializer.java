package com.chronicle.core.event;

/**
 * Contract for serializing and deserializing domain events and aggregate state.
 *
 * @param <S> the aggregate state type
 */
public interface EventSerializer<S> {

    /**
     * Serializes a domain event to a JSON string.
     *
     * @param event the event to serialize
     * @return JSON string representation
     */
    String serialize(DomainEvent event);

    /**
     * Deserializes a JSON string back to a domain event.
     *
     * @param payload   JSON string
     * @param eventType registered event type name
     * @return deserialized domain event
     */
    DomainEvent deserialize(String payload, String eventType);

    /**
     * Serializes aggregate state to a JSON string for snapshot storage.
     *
     * @param state the aggregate state
     * @return JSON string representation
     */
    String serializeState(S state);

    /**
     * Deserializes aggregate state from a JSON string.
     *
     * @param json      JSON string
     * @param stateType the class of the state
     * @return deserialized aggregate state
     */
    S deserializeState(String json, Class<S> stateType);
}
