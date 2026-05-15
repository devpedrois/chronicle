package com.chronicle.core.serialization;

import com.chronicle.core.event.DomainEvent;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JacksonEventSerializerTest {

    record DepositEvent(long amountCents, String description) implements DomainEvent {}
    record TimestampedEvent(String name, Instant occurredAt) implements DomainEvent {}

    private EventTypeRegistry registry;
    private JacksonEventSerializer<Object> serializer;

    @BeforeEach
    void setUp() {
        registry = new EventTypeRegistry();
        registry.register("DepositEvent", DepositEvent.class);
        registry.register("TimestampedEvent", TimestampedEvent.class);
        serializer = new JacksonEventSerializer<>(registry);
    }

    @Test
    @DisplayName("serialize and deserialize roundtrip preserves all fields")
    void shouldRoundtripEvent() {
        DepositEvent original = new DepositEvent(9999L, "salary");

        String json = serializer.serialize(original);
        DomainEvent result = serializer.deserialize(json, "DepositEvent");

        assertThat(result).isInstanceOf(DepositEvent.class);
        DepositEvent deserialized = (DepositEvent) result;
        assertThat(deserialized.amountCents()).isEqualTo(9999L);
        assertThat(deserialized.description()).isEqualTo("salary");
    }

    @Test
    @DisplayName("deserialize unknown type throws exception")
    // [SECURITY] Test: Jackson rejects unknown event type — whitelist-only
    void shouldRejectUnknownEventType() {
        assertThatThrownBy(() -> serializer.deserialize("{}", "UnregisteredEvent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown event type");
    }

    @Test
    @DisplayName("deserialize JSON with unknown field throws exception")
    // [SECURITY] Test: Jackson rejects unknown fields — prevents mass assignment / data injection
    void shouldRejectUnknownFieldInJson() {
        String jsonWithExtraField = "{\"amountCents\":100,\"description\":\"ok\",\"injected\":\"evil\"}";

        assertThatThrownBy(() -> serializer.deserialize(jsonWithExtraField, "DepositEvent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasRootCauseInstanceOf(UnrecognizedPropertyException.class);
    }

    @Test
    @DisplayName("serialize preserves Instant fields correctly")
    void shouldPreserveInstantFields() {
        Instant now = Instant.parse("2026-05-15T10:00:00Z");
        TimestampedEvent original = new TimestampedEvent("test", now);

        String json = serializer.serialize(original);
        DomainEvent result = serializer.deserialize(json, "TimestampedEvent");

        assertThat(result).isInstanceOf(TimestampedEvent.class);
        assertThat(((TimestampedEvent) result).occurredAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("deserialize with null primitive throws exception")
    // [SECURITY] Test: null primitive rejected — prevents NullPointerException in domain logic
    void shouldRejectNullForPrimitive() {
        String jsonWithNullLong = "{\"amountCents\":null,\"description\":\"test\"}";

        assertThatThrownBy(() -> serializer.deserialize(jsonWithNullLong, "DepositEvent"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("serializeState and deserializeState roundtrip")
    void shouldRoundtripState() {
        record SomeState(String name, long value) {}
        JacksonEventSerializer<SomeState> stateSerializer = new JacksonEventSerializer<>(registry);

        SomeState state = new SomeState("demo", 42L);
        String json = stateSerializer.serializeState(state);
        SomeState restored = stateSerializer.deserializeState(json, SomeState.class);

        assertThat(restored.name()).isEqualTo("demo");
        assertThat(restored.value()).isEqualTo(42L);
    }
}
