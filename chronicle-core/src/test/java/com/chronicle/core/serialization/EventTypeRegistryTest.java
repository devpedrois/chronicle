package com.chronicle.core.serialization;

import com.chronicle.core.event.DomainEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventTypeRegistryTest {

    record TestEvent(String name) implements DomainEvent {}
    record OtherEvent(int value) implements DomainEvent {}

    private EventTypeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new EventTypeRegistry();
    }

    @Test
    void shouldResolveRegisteredType() {
        registry.register("TestEvent", TestEvent.class);

        assertThat(registry.resolve("TestEvent")).isEqualTo(TestEvent.class);
    }

    @Test
    // [SECURITY] Test: unknown type rejected — whitelist-only, no dynamic class loading
    void shouldRejectUnknownType() {
        registry.register("TestEvent", TestEvent.class);

        assertThatThrownBy(() -> registry.resolve("UnknownEvent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown event type");
    }

    @Test
    void shouldReturnFalseForUnregisteredType() {
        assertThat(registry.isRegistered("Anything")).isFalse();
    }

    @Test
    void shouldReturnTypeNameForRegisteredClass() {
        registry.register("TestEvent", TestEvent.class);

        assertThat(registry.typeNameFor(TestEvent.class)).isEqualTo("TestEvent");
    }

    @Test
    void shouldThrowOnDuplicateRegistration() {
        registry.register("TestEvent", TestEvent.class);

        assertThatThrownBy(() -> registry.register("TestEvent", OtherEvent.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void shouldThrowTypeNameForUnregisteredClass() {
        assertThatThrownBy(() -> registry.typeNameFor(TestEvent.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not registered");
    }

    @Test
    void shouldRejectBlankTypeName() {
        assertThatThrownBy(() -> registry.register("  ", TestEvent.class))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
