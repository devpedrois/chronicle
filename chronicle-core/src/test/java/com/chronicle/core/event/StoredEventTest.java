package com.chronicle.core.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class StoredEventTest {

    @Test
    void shouldCreateValidStoredEvent() {
        UUID id = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        StoredEvent event = new StoredEvent(id, aggregateId, "BankAccount", "AccountCreated", "{}", 1, Instant.now());

        assertThat(event.eventId()).isEqualTo(id);
        assertThat(event.aggregateId()).isEqualTo(aggregateId);
        assertThat(event.version()).isEqualTo(1);
    }

    @Test
    void shouldRejectVersionZero() {
        assertThatThrownBy(() ->
                new StoredEvent(UUID.randomUUID(), UUID.randomUUID(), "T", "E", "{}", 0, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version must be >= 1");
    }

    @Test
    void shouldRejectNegativeVersion() {
        assertThatThrownBy(() ->
                new StoredEvent(UUID.randomUUID(), UUID.randomUUID(), "T", "E", "{}", -1, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version must be >= 1");
    }

    @Test
    // [SECURITY] Test: payload size limit prevents DoS
    void shouldRejectPayloadExceeding64KB() {
        String bigPayload = "x".repeat(65537);
        assertThatThrownBy(() ->
                new StoredEvent(UUID.randomUUID(), UUID.randomUUID(), "T", "E", bigPayload, 1, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload exceeds 64KB limit");
    }

    @Test
    void shouldRejectNullFields() {
        assertThatThrownBy(() ->
                new StoredEvent(null, UUID.randomUUID(), "T", "E", "{}", 1, Instant.now()))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() ->
                new StoredEvent(UUID.randomUUID(), null, "T", "E", "{}", 1, Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }
}
