package com.chronicle.core.serialization;

import com.chronicle.core.event.DomainEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventTypeRegistryTest {

    record TestEvent(String name) implements DomainEvent {}

    @Test
    void shouldResolveRegisteredType() {
        EventTypeRegistry registry = EventTypeRegistry.builder()
                .register("TestEvent", TestEvent.class)
                .build();

        assertThat(registry.resolve("TestEvent")).isEqualTo(TestEvent.class);
    }

    @Test
    // [SECURITY] Test: Jackson deserialization whitelist — unknown type rejected immediately
    void shouldRejectUnknownType() {
        EventTypeRegistry registry = EventTypeRegistry.builder()
                .register("TestEvent", TestEvent.class)
                .build();

        assertThatThrownBy(() -> registry.resolve("UnknownEvent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown event type");
    }

    @Test
    void shouldReturnFalseForUnregisteredType() {
        EventTypeRegistry registry = EventTypeRegistry.builder().build();
        assertThat(registry.isRegistered("Anything")).isFalse();
    }
}
