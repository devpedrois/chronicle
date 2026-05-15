package com.chronicle.jdbc;

import com.chronicle.core.event.StoredEvent;
import com.chronicle.core.store.ConcurrentModificationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcEventStoreTest extends AbstractPostgresTest {

    @Autowired
    private JdbcEventStore eventStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        // Events are immutable — trigger blocks UPDATE/DELETE — truncate as superuser is still possible in tests
        jdbcTemplate.execute("TRUNCATE TABLE events RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("should save and load events in order")
    void shouldSaveAndLoadEventsInOrder() {
        UUID aggregateId = UUID.randomUUID();
        String payload1 = "{\"type\":\"AccountCreated\",\"owner\":\"Alice\"}";
        String payload2 = "{\"type\":\"MoneyDeposited\",\"amount\":500}";
        String payload3 = "{\"type\":\"MoneyWithdrawn\",\"amount\":100}";

        List<StoredEvent> events = List.of(
                new StoredEvent(UUID.randomUUID(), aggregateId, "BankAccount", "AccountCreated", payload1, 1, Instant.now()),
                new StoredEvent(UUID.randomUUID(), aggregateId, "BankAccount", "MoneyDeposited", payload2, 2, Instant.now()),
                new StoredEvent(UUID.randomUUID(), aggregateId, "BankAccount", "MoneyWithdrawn", payload3, 3, Instant.now())
        );

        eventStore.save(aggregateId, "BankAccount", events, 0);

        List<StoredEvent> loaded = eventStore.load(aggregateId);

        assertThat(loaded).hasSize(3);
        assertThat(loaded.get(0).version()).isEqualTo(1);
        assertThat(loaded.get(0).eventType()).isEqualTo("AccountCreated");
        assertThat(loaded.get(0).aggregateId()).isEqualTo(aggregateId);
        assertThat(loaded.get(0).payload()).contains("Alice");

        assertThat(loaded.get(1).version()).isEqualTo(2);
        assertThat(loaded.get(1).eventType()).isEqualTo("MoneyDeposited");

        assertThat(loaded.get(2).version()).isEqualTo(3);
        assertThat(loaded.get(2).eventType()).isEqualTo("MoneyWithdrawn");
    }

    @Test
    @DisplayName("should return empty list for unknown aggregate")
    void shouldReturnEmptyListForUnknownAggregate() {
        List<StoredEvent> result = eventStore.load(UUID.randomUUID());

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("should load events after specific version")
    void shouldLoadEventsAfterSpecificVersion() {
        UUID aggregateId = UUID.randomUUID();
        List<StoredEvent> events = List.of(
                new StoredEvent(UUID.randomUUID(), aggregateId, "BankAccount", "Event1", "{\"v\":1}", 1, Instant.now()),
                new StoredEvent(UUID.randomUUID(), aggregateId, "BankAccount", "Event2", "{\"v\":2}", 2, Instant.now()),
                new StoredEvent(UUID.randomUUID(), aggregateId, "BankAccount", "Event3", "{\"v\":3}", 3, Instant.now()),
                new StoredEvent(UUID.randomUUID(), aggregateId, "BankAccount", "Event4", "{\"v\":4}", 4, Instant.now()),
                new StoredEvent(UUID.randomUUID(), aggregateId, "BankAccount", "Event5", "{\"v\":5}", 5, Instant.now())
        );

        eventStore.save(aggregateId, "BankAccount", events, 0);

        List<StoredEvent> afterVersion3 = eventStore.loadAfterVersion(aggregateId, 3);

        assertThat(afterVersion3).hasSize(2);
        assertThat(afterVersion3.get(0).version()).isEqualTo(4);
        assertThat(afterVersion3.get(1).version()).isEqualTo(5);
    }

    @Test
    @DisplayName("should detect version conflict via optimistic locking")
    void shouldDetectVersionConflict() {
        UUID aggregateId = UUID.randomUUID();
        StoredEvent firstEvent = new StoredEvent(
                UUID.randomUUID(), aggregateId, "BankAccount", "AccountCreated", "{\"owner\":\"Bob\"}", 1, Instant.now());

        eventStore.save(aggregateId, "BankAccount", List.of(firstEvent), 0);

        // Attempt to save another event with the same version — simulates concurrent writer
        StoredEvent conflictingEvent = new StoredEvent(
                UUID.randomUUID(), aggregateId, "BankAccount", "MoneyDeposited", "{\"amount\":100}", 1, Instant.now());

        assertThatThrownBy(() -> eventStore.save(aggregateId, "BankAccount", List.of(conflictingEvent), 0))
                .isInstanceOf(ConcurrentModificationException.class);
    }

    @Test
    @DisplayName("should enforce event immutability at database level")
    // [SECURITY] Test: database-level immutability enforcement via trigger
    void shouldEnforceEventImmutabilityAtDatabaseLevel() {
        UUID aggregateId = UUID.randomUUID();
        StoredEvent event = new StoredEvent(
                UUID.randomUUID(), aggregateId, "BankAccount", "AccountCreated", "{\"owner\":\"Carol\"}", 1, Instant.now());

        eventStore.save(aggregateId, "BankAccount", List.of(event), 0);

        // Trigger must fire and raise an exception when UPDATE is attempted
        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "UPDATE events SET event_type = 'HACKED' WHERE aggregate_id = ?",
                        aggregateId))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("should reject payload exceeding 64KB")
    // [SECURITY] Test: payload size limit prevents DoS via large payloads
    void shouldRejectPayloadExceeding64KB() {
        UUID aggregateId = UUID.randomUUID();
        String oversizedPayload = "{\"data\":\"" + "x".repeat(65530) + "\"}";

        assertThatThrownBy(() ->
                new StoredEvent(UUID.randomUUID(), aggregateId, "BankAccount", "BigEvent", oversizedPayload, 1, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload exceeds 64KB limit");
    }

    @Test
    @DisplayName("should store SQL injection attempt as literal text")
    // [SECURITY] Test: SQL injection in JSONB payload is harmless — stored as literal text via parameterized query
    void shouldStoreSqlInjectionAttemptAsLiteralText() {
        UUID aggregateId = UUID.randomUUID();
        String injectionAttempt = "{\"description\": \"'; DROP TABLE events; --\"}";

        StoredEvent event = new StoredEvent(
                UUID.randomUUID(), aggregateId, "BankAccount", "AccountCreated", injectionAttempt, 1, Instant.now());

        eventStore.save(aggregateId, "BankAccount", List.of(event), 0);

        List<StoredEvent> loaded = eventStore.load(aggregateId);
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).payload()).contains("DROP TABLE events");

        // Verify the events table still exists and was not dropped by the injection attempt
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM events", Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(1);
    }
}
