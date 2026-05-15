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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adversarial security tests for JdbcEventStore.
 * Each test models a specific attack vector or security invariant.
 */
class JdbcEventStoreSecurityTest extends AbstractPostgresTest {

    @Autowired
    private JdbcEventStore eventStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE TABLE events RESTART IDENTITY CASCADE");
    }

    // ─── Input Validation Attacks ────────────────────────────────────────────

    @Test
    @DisplayName("[SECURITY] should reject blank aggregateType — prevents corrupt event streams")
    void shouldRejectBlankAggregateType() {
        // Validation fires at StoredEvent construction — defense in depth at the earliest point
        UUID aggregateId = UUID.randomUUID();
        assertThatThrownBy(() ->
                new StoredEvent(UUID.randomUUID(), aggregateId, "   ", "AccountCreated", "{}", 1, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregateType");
    }

    @Test
    @DisplayName("[SECURITY] should reject blank eventType — prevents corrupt event type registry lookups")
    void shouldRejectBlankEventType() {
        UUID aggregateId = UUID.randomUUID();
        assertThatThrownBy(() ->
                new StoredEvent(UUID.randomUUID(), aggregateId, "BankAccount", "   ", "{}", 1, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
    }

    @Test
    @DisplayName("[SECURITY] should reject oversized aggregateType — fail-fast prevents DB error exposure")
    void shouldRejectOversizedAggregateType() {
        UUID aggregateId = UUID.randomUUID();
        String oversized = "A".repeat(256);
        assertThatThrownBy(() ->
                new StoredEvent(UUID.randomUUID(), aggregateId, oversized, "AccountCreated", "{}", 1, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregateType");
    }

    @Test
    @DisplayName("[SECURITY] should reject oversized eventType — fail-fast prevents DB error exposure")
    void shouldRejectOversizedEventType() {
        UUID aggregateId = UUID.randomUUID();
        String oversized = "E".repeat(256);
        assertThatThrownBy(() ->
                new StoredEvent(UUID.randomUUID(), aggregateId, "BankAccount", oversized, "{}", 1, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
    }

    @Test
    @DisplayName("[SECURITY] should reject invalid JSON payload — DB cast error must not reach caller raw")
    void shouldRejectInvalidJsonPayload() {
        UUID aggregateId = UUID.randomUUID();
        String notJson = "not-valid-json{{{{";
        StoredEvent event = new StoredEvent(
                UUID.randomUUID(), aggregateId, "BankAccount", "AccountCreated", notJson, 1, Instant.now());

        assertThatThrownBy(() -> eventStore.save(aggregateId, "BankAccount", List.of(event), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");
    }

    // ─── Information Disclosure Attacks ──────────────────────────────────────

    @Test
    @DisplayName("[SECURITY] negative afterVersion must be rejected — prevents full event stream dump")
    void shouldRejectNegativeAfterVersion() {
        UUID aggregateId = UUID.randomUUID();
        StoredEvent event = new StoredEvent(
                UUID.randomUUID(), aggregateId, "BankAccount", "AccountCreated", "{}", 1, Instant.now());
        eventStore.save(aggregateId, "BankAccount", List.of(event), 0);

        assertThatThrownBy(() -> eventStore.loadAfterVersion(aggregateId, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("afterVersion");
    }

    @Test
    @DisplayName("[SECURITY] SQL injection in aggregateType stored as literal text — parameterized query proof")
    void shouldStoreSqlInjectionInAggregateTypeAsLiteralText() {
        UUID aggregateId = UUID.randomUUID();
        String injection = "'; DROP TABLE events; --";
        StoredEvent event = new StoredEvent(
                UUID.randomUUID(), aggregateId, injection, "AccountCreated", "{\"v\":1}", 1, Instant.now());

        eventStore.save(aggregateId, injection, List.of(event), 0);

        List<StoredEvent> loaded = eventStore.load(aggregateId);
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).aggregateType()).isEqualTo(injection);

        // Table must still exist — injection had no effect
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM events", Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("[SECURITY] SQL injection in eventType stored as literal text — parameterized query proof")
    void shouldStoreSqlInjectionInEventTypeAsLiteralText() {
        UUID aggregateId = UUID.randomUUID();
        String injection = "AccountCreated'; UPDATE events SET event_type='HACKED'; --";
        StoredEvent event = new StoredEvent(
                UUID.randomUUID(), aggregateId, "BankAccount", injection, "{\"v\":1}", 1, Instant.now());

        eventStore.save(aggregateId, "BankAccount", List.of(event), 0);

        List<StoredEvent> loaded = eventStore.load(aggregateId);
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).eventType()).isEqualTo(injection);

        // Verify no events were updated — immutability preserved
        Integer hacked = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM events WHERE event_type = 'HACKED'", Integer.class);
        assertThat(hacked).isEqualTo(0);
    }

    // ─── Data Isolation ───────────────────────────────────────────────────────

    @Test
    @DisplayName("[SECURITY] aggregate data isolation — load must return only that aggregate's events")
    void shouldIsolateDataBetweenAggregates() {
        UUID aggregateA = UUID.randomUUID();
        UUID aggregateB = UUID.randomUUID();

        eventStore.save(aggregateA, "BankAccount", List.of(
                new StoredEvent(UUID.randomUUID(), aggregateA, "BankAccount", "AccountCreated", "{\"owner\":\"Alice\"}", 1, Instant.now()),
                new StoredEvent(UUID.randomUUID(), aggregateA, "BankAccount", "MoneyDeposited", "{\"amount\":1000}", 2, Instant.now())
        ), 0);

        eventStore.save(aggregateB, "BankAccount", List.of(
                new StoredEvent(UUID.randomUUID(), aggregateB, "BankAccount", "AccountCreated", "{\"owner\":\"Bob\"}", 1, Instant.now())
        ), 0);

        List<StoredEvent> loadedA = eventStore.load(aggregateA);
        List<StoredEvent> loadedB = eventStore.load(aggregateB);

        assertThat(loadedA).hasSize(2);
        assertThat(loadedA).allMatch(e -> e.aggregateId().equals(aggregateA));

        assertThat(loadedB).hasSize(1);
        assertThat(loadedB).allMatch(e -> e.aggregateId().equals(aggregateB));

        // Cross-contamination: none of A's events appear in B's stream and vice versa
        assertThat(loadedA).noneMatch(e -> e.aggregateId().equals(aggregateB));
        assertThat(loadedB).noneMatch(e -> e.aggregateId().equals(aggregateA));
    }

    @Test
    @DisplayName("[SECURITY] loadAfterVersion data isolation — only target aggregate events returned")
    void shouldIsolateDataInLoadAfterVersion() {
        UUID aggregateA = UUID.randomUUID();
        UUID aggregateB = UUID.randomUUID();

        eventStore.save(aggregateA, "BankAccount", List.of(
                new StoredEvent(UUID.randomUUID(), aggregateA, "BankAccount", "Event1", "{}", 1, Instant.now()),
                new StoredEvent(UUID.randomUUID(), aggregateA, "BankAccount", "Event2", "{}", 2, Instant.now()),
                new StoredEvent(UUID.randomUUID(), aggregateA, "BankAccount", "Event3", "{}", 3, Instant.now())
        ), 0);

        // aggregateB has event at version 1 — must NOT appear in aggregateA's loadAfterVersion
        eventStore.save(aggregateB, "BankAccount", List.of(
                new StoredEvent(UUID.randomUUID(), aggregateB, "BankAccount", "OtherEvent", "{}", 1, Instant.now())
        ), 0);

        List<StoredEvent> result = eventStore.loadAfterVersion(aggregateA, 1);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(e -> e.aggregateId().equals(aggregateA));
        assertThat(result).noneMatch(e -> e.aggregateId().equals(aggregateB));
    }

    // ─── Concurrency / Optimistic Locking ────────────────────────────────────

    @Test
    @DisplayName("[SECURITY] concurrent writers — only one wins, rest get ConcurrentModificationException")
    void shouldHandleConcurrentWritersCorrectly() throws InterruptedException {
        UUID aggregateId = UUID.randomUUID();
        StoredEvent firstEvent = new StoredEvent(
                UUID.randomUUID(), aggregateId, "BankAccount", "AccountCreated", "{}", 1, Instant.now());
        eventStore.save(aggregateId, "BankAccount", List.of(firstEvent), 0);

        int threads = 5;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger conflicts = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    StoredEvent e = new StoredEvent(
                            UUID.randomUUID(), aggregateId, "BankAccount", "MoneyDeposited",
                            "{\"amount\":100}", 2, Instant.now());
                    eventStore.save(aggregateId, "BankAccount", List.of(e), 1);
                    successes.incrementAndGet();
                } catch (ConcurrentModificationException ex) {
                    conflicts.incrementAndGet();
                } catch (Exception ignored) {
                    conflicts.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        executor.shutdown();

        // [SECURITY] Optimistic locking: exactly one writer wins, others get conflict
        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);

        // DB has exactly 2 events (version 1 and 2) — no duplicates, no data corruption
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM events WHERE aggregate_id = ?", Integer.class, aggregateId);
        assertThat(count).isEqualTo(2);
    }

    // ─── Immutability at DB Level ─────────────────────────────────────────────

    @Test
    @DisplayName("[SECURITY] DELETE via JDBC must be blocked by trigger")
    void shouldBlockDeleteViaTrigger() {
        UUID aggregateId = UUID.randomUUID();
        eventStore.save(aggregateId, "BankAccount", List.of(
                new StoredEvent(UUID.randomUUID(), aggregateId, "BankAccount", "AccountCreated", "{}", 1, Instant.now())
        ), 0);

        assertThatThrownBy(() ->
                jdbcTemplate.update("DELETE FROM events WHERE aggregate_id = ?", aggregateId))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("immutable");
    }
}
