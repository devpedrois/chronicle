package com.chronicle.core.serialization;

import com.chronicle.core.event.DomainEvent;
import com.chronicle.core.event.EventSerializer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Jackson-based event serializer with hardened security configuration.
 *
 * // [SECURITY] Jackson Deserialization Safety:
 * // - FAIL_ON_UNKNOWN_PROPERTIES=true: rejects unexpected fields (defense against injection)
 * // - No activateDefaultTyping() / enableDefaultTyping(): prevents gadget-chain RCE (CVE-2017-7525)
 * // - EventTypeRegistry whitelist: only known types can be deserialized
 *
 * @param <S> the aggregate state type
 */
public class JacksonEventSerializer<S> implements EventSerializer<S> {

    private final ObjectMapper mapper;
    private final EventTypeRegistry typeRegistry;

    public JacksonEventSerializer(EventTypeRegistry typeRegistry) {
        this.typeRegistry = typeRegistry;
        // [SECURITY] NEVER call activateDefaultTyping() or enableDefaultTyping() here
        // Those features enable polymorphic type handling that allows gadget-chain attacks
        this.mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // [SECURITY] Reject null for primitives — prevents NullPointerException in domain logic
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                // [SECURITY] Block unsafe polymorphic deserialization — prevents gadget chain RCE (CVE-2017-7525, CVE-2019-12086)
                .enable(MapperFeature.BLOCK_UNSAFE_POLYMORPHIC_BASE_TYPES)
                .build();
    }

    @Override
    public String serialize(DomainEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize event: " + event.getClass().getSimpleName(), e);
        }
    }

    @Override
    public DomainEvent deserialize(String payload, String eventType) {
        // [SECURITY] Whitelist check before any deserialization — unknown types never reach Jackson
        Class<? extends DomainEvent> clazz = typeRegistry.resolve(eventType);
        try {
            return mapper.readValue(payload, clazz);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to deserialize event of type '" + eventType + "'", e);
        }
    }

    @Override
    public String serializeState(S state) {
        try {
            return mapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize state: " + state.getClass().getSimpleName(), e);
        }
    }

    @Override
    public S deserializeState(String json, Class<S> stateType) {
        try {
            return mapper.readValue(json, stateType);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize state", e);
        }
    }

    /**
     * Resolves event type name via the whitelist registry — guarantees the stored name
     * round-trips correctly through {@link #deserialize(String, String)}.
     */
    @Override
    public String typeNameFor(DomainEvent event) {
        // [SECURITY] Registry lookup — fails fast if event class was not registered,
        // preventing getSimpleName() drift when a class is renamed after the registry mapping.
        return typeRegistry.typeNameFor(event.getClass());
    }
}
