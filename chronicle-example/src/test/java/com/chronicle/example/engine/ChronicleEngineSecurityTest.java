package com.chronicle.example.engine;

import com.chronicle.core.aggregate.AggregateRoot;
import com.chronicle.core.engine.ChronicleEngine;
import com.chronicle.core.event.StoredEvent;
import com.chronicle.core.serialization.EventTypeRegistry;
import com.chronicle.core.serialization.JacksonEventSerializer;
import com.chronicle.core.store.ConcurrentModificationException;
import com.chronicle.example.AbstractEngineTest;
import com.chronicle.example.domain.BankAccount;
import com.chronicle.example.domain.BankAccountState;
import com.chronicle.example.domain.event.AccountCreated;
import com.chronicle.example.domain.event.MoneyDeposited;
import com.chronicle.example.domain.event.MoneyTransferred;
import com.chronicle.example.domain.event.MoneyWithdrawn;
import com.chronicle.jdbc.JdbcEventStore;
import com.chronicle.jdbc.JdbcSnapshotStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adversarial security tests for ChronicleEngine.
 * Each test is named after the attack vector or invariant it validates.
 * "How would an attacker exploit this?"
 */
class ChronicleEngineSecurityTest extends AbstractEngineTest {

    @Autowired
    private JdbcEventStore eventStore;

    @Autowired
    private JdbcSnapshotStore snapshotStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ChronicleEngine<BankAccountState> engine;
    private BankAccount bankAccount;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE events RESTART IDENTITY CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE snapshots");

        EventTypeRegistry registry = new EventTypeRegistry();
        registry.register("AccountCreated", AccountCreated.class);
        registry.register("MoneyDeposited", MoneyDeposited.class);
        registry.register("MoneyWithdrawn", MoneyWithdrawn.class);
        registry.register("MoneyTransferred", MoneyTransferred.class);

        JacksonEventSerializer<BankAccountState> serializer = new JacksonEventSerializer<>(registry);
        bankAccount = new BankAccount();
        engine = new ChronicleEngine<>(
                eventStore, snapshotStore, serializer,
                (current, last) -> false,
                bankAccount,
                BankAccountState.class
        );
    }

    // ─── Null Safety ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("[SECURITY] null root in save() rejected — prevents silent NPE masking partial persistence")
    void shouldRejectNullRootOnSave() {
        assertThatThrownBy(() -> engine.save(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("root must not be null");
    }

    @Test
    @DisplayName("[SECURITY] null aggregateId in load() rejected — null queries all rows matching NULL (zero), masking caller bug")
    void shouldRejectNullAggregateIdOnLoad() {
        assertThatThrownBy(() -> engine.load(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("aggregateId must not be null");
    }

    @Test
    @DisplayName("[SECURITY] root without ID in save() rejected — null aggregate_id corrupts stream isolation in DB")
    void shouldRejectRootWithNullId() {
        // Simulate a root created via factory but then stripped of its ID.
        // An attacker could use this to insert events with NULL aggregate_id,
        // making them unreachable by any legitimate load() call.
        AggregateRoot<BankAccountState> root = BankAccount.create("Victim");
        root.setId(null); // simulate ID stripping after creation

        assertThatThrownBy(() -> engine.save(root))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("root.id must not be null");
    }

    // ─── Unknown Aggregate ────────────────────────────────────────────────────

    @Test
    @DisplayName("[SECURITY] unknown aggregateId returns empty Optional — no exception leaks internal state")
    void shouldReturnEmptyForUnknownAggregate() {
        Optional<AggregateRoot<BankAccountState>> result = engine.load(UUID.randomUUID());

        assertThat(result).isEmpty();
    }

    // ─── Optimistic Locking — Phase 1 Explicit ────────────────────────────────

    @Test
    @DisplayName("[SECURITY] Phase 1 version check: CME carries correct actualVersion from DB — not sentinel -1")
    void shouldExposeActualVersionInConcurrentModificationException() {
        // Setup: account with 3 events (version 3)
        AggregateRoot<BankAccountState> root = BankAccount.create("Attacker");
        bankAccount.deposit(root, 200L, "d1");
        bankAccount.deposit(root, 300L, "d2");
        engine.save(root);
        UUID accountId = root.getId();

        // Load stale root (version 3) — DB is at version 3
        AggregateRoot<BankAccountState> stale = engine.load(accountId).orElseThrow();

        // Advance DB to version 4
        AggregateRoot<BankAccountState> fresh = engine.load(accountId).orElseThrow();
        bankAccount.deposit(fresh, 100L, "advance");
        engine.save(fresh);

        // Stale root (expectedVersion=3) conflicts with DB version=4
        // Phase 1 SELECT MAX returns 4, exposing it in the exception
        bankAccount.deposit(stale, 50L, "stale write");
        assertThatThrownBy(() -> engine.save(stale))
                .isInstanceOf(ConcurrentModificationException.class)
                .satisfies(e -> {
                    ConcurrentModificationException cme = (ConcurrentModificationException) e;
                    assertThat(cme.getAggregateId()).isEqualTo(accountId);
                    assertThat(cme.getExpectedVersion()).isEqualTo(3);
                    // Phase 1 found the real version — not -1 sentinel
                    assertThat(cme.getActualVersion()).isEqualTo(4);
                });
    }

    @Test
    @DisplayName("[SECURITY] CME aggregateId matches the conflicting aggregate — no ID confusion across streams")
    void shouldCarryCorrectAggregateIdInConcurrentModificationException() {
        AggregateRoot<BankAccountState> a = BankAccount.create("Alice");
        engine.save(a);
        AggregateRoot<BankAccountState> b = BankAccount.create("Bob");
        engine.save(b);

        // Load A twice — simulate concurrent readers on the same stream
        AggregateRoot<BankAccountState> aFirst = engine.load(a.getId()).orElseThrow();
        AggregateRoot<BankAccountState> aSecond = engine.load(a.getId()).orElseThrow();

        bankAccount.deposit(aFirst, 100L, "first");
        engine.save(aFirst);

        bankAccount.deposit(aSecond, 200L, "second stale");
        assertThatThrownBy(() -> engine.save(aSecond))
                .isInstanceOf(ConcurrentModificationException.class)
                .satisfies(e -> {
                    ConcurrentModificationException cme = (ConcurrentModificationException) e;
                    // CME references A's ID, not B's — streams do not bleed into each other
                    assertThat(cme.getAggregateId()).isEqualTo(a.getId());
                    assertThat(cme.getAggregateId()).isNotEqualTo(b.getId());
                });
    }

    // ─── Atomic Batch — Transactional Integrity ───────────────────────────────

    @Test
    @DisplayName("[SECURITY] multi-event batch is atomic — if second event fails, first is rolled back too")
    void shouldRollBackEntireBatchOnPartialFailure() {
        UUID aggregateId = UUID.randomUUID();

        // First batch: version 1 succeeds
        StoredEvent first = new StoredEvent(
                UUID.randomUUID(), aggregateId, "BankAccount", "AccountCreated",
                "{\"accountId\":\"" + aggregateId + "\",\"ownerName\":\"Dave\"}", 1, Instant.now());
        eventStore.save(aggregateId, "BankAccount", List.of(first), 0);

        // Second batch: version 2 (good) + version 1 (duplicate — forces UNIQUE violation mid-batch)
        // [SECURITY] If batch is NOT atomic, version 2 would land even though version 1 duplicate fails.
        // Attacker could exploit this to create event gaps or corrupt aggregate history.
        StoredEvent good = new StoredEvent(
                UUID.randomUUID(), aggregateId, "BankAccount", "MoneyDeposited",
                "{\"amountCents\":500,\"description\":\"legit\"}", 2, Instant.now());
        StoredEvent duplicate = new StoredEvent(
                UUID.randomUUID(), aggregateId, "BankAccount", "MoneyDeposited",
                "{\"amountCents\":999,\"description\":\"duplicate\"}", 1, Instant.now());

        assertThatThrownBy(() -> eventStore.save(aggregateId, "BankAccount", List.of(good, duplicate), 1))
                .isInstanceOf(ConcurrentModificationException.class);

        // After failure: only version 1 exists — version 2 was rolled back with the batch
        List<StoredEvent> events = eventStore.load(aggregateId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).version()).isEqualTo(1);
        assertThat(events.get(0).eventType()).isEqualTo("AccountCreated");
    }

    // ─── State Consistency After Conflict ─────────────────────────────────────

    @Test
    @DisplayName("[SECURITY] reloaded root after CME is consistent — no phantom state from failed write")
    void shouldReturnConsistentStateAfterConflict() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Eve");
        bankAccount.deposit(root, 1000L, "seed");
        engine.save(root);
        UUID accountId = root.getId();

        AggregateRoot<BankAccountState> stale = engine.load(accountId).orElseThrow();
        AggregateRoot<BankAccountState> winner = engine.load(accountId).orElseThrow();

        // Winner commits a withdrawal
        bankAccount.withdraw(winner, 400L, "winner");
        engine.save(winner);

        // Stale writer fails with CME
        bankAccount.deposit(stale, 9999L, "phantom deposit");
        assertThatThrownBy(() -> engine.save(stale))
                .isInstanceOf(ConcurrentModificationException.class);

        // Reload — must see winner's state, not stale's uncommitted events
        AggregateRoot<BankAccountState> consistent = engine.load(accountId).orElseThrow();
        assertThat(consistent.getState().balanceCents())
                .as("Phantom deposit must not appear after CME — DB must reflect only winner's commit")
                .isEqualTo(600L);
        assertThat(consistent.getVersion()).isEqualTo(3);
        assertThat(consistent.getUncommittedEvents()).isEmpty();
    }

    // ─── Immutability Under Concurrent Writes ─────────────────────────────────

    @Test
    @DisplayName("[SECURITY] DB trigger blocks direct UPDATE on events — immutability enforced at DB level")
    void shouldBlockDirectUpdateOnEvents() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Mallory");
        engine.save(root);

        // Attacker tries to mutate an event directly in the DB
        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "UPDATE events SET event_type = 'HACKED' WHERE aggregate_id = ?",
                        root.getId()))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("[SECURITY] DB trigger blocks direct DELETE on events — append-only enforced at DB level")
    void shouldBlockDirectDeleteOnEvents() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Mallory");
        engine.save(root);

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "DELETE FROM events WHERE aggregate_id = ?",
                        root.getId()))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("immutable");
    }

    // ─── Version Monotonicity ─────────────────────────────────────────────────

    @Test
    @DisplayName("[SECURITY] version increments monotonically across sequential saves — no gaps, no duplicates")
    void shouldMaintainMonotonicVersionAcrossManySaves() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Frank");
        engine.save(root);
        UUID accountId = root.getId();

        for (int i = 1; i <= 9; i++) {
            AggregateRoot<BankAccountState> r = engine.load(accountId).orElseThrow();
            bankAccount.deposit(r, 10L, "deposit " + i);
            engine.save(r);
        }

        List<StoredEvent> events = eventStore.load(accountId);
        assertThat(events).hasSize(10);

        // Verify no gaps and no duplicates in version sequence
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).version())
                    .as("Version at index %d must be %d — gaps or duplicates indicate lost or double-written events", i, i + 1)
                    .isEqualTo(i + 1);
        }
    }

    // ─── Thundering Herd with Jitter ──────────────────────────────────────────

    @Test
    @DisplayName("[SECURITY] 20 concurrent threads with jitter backoff — all deposits land exactly once")
    void shouldHandleHighConcurrencyWithJitter() throws InterruptedException {
        AggregateRoot<BankAccountState> initial = BankAccount.create("George");
        engine.save(initial);
        UUID accountId = initial.getId();

        int threadCount = 20;
        int maxRetries = 10;
        AtomicInteger failures = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    doneLatch.countDown();
                    return;
                }
                boolean succeeded = false;
                for (int attempt = 0; attempt < maxRetries && !succeeded; attempt++) {
                    try {
                        AggregateRoot<BankAccountState> root = engine.load(accountId).orElseThrow();
                        bankAccount.deposit(root, 50L, "stress deposit");
                        engine.save(root);
                        succeeded = true;
                    } catch (ConcurrentModificationException e) {
                        if (attempt < maxRetries - 1) {
                            try {
                                // [SECURITY] Jitter prevents synchronized retry storms
                                // Without jitter, all threads back off and retry simultaneously,
                                // causing the same contention at the next window.
                                long jitter = (long) (Math.random() * 20);
                                Thread.sleep(5L * (1L << Math.min(attempt, 5)) + jitter);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
                if (!succeeded) {
                    failures.incrementAndGet();
                }
                doneLatch.countDown();
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(90, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(failures.get())
                .as("No thread should exhaust retries — all deposits must eventually land")
                .isZero();

        AggregateRoot<BankAccountState> result = engine.load(accountId).orElseThrow();
        assertThat(result.getState().balanceCents())
                .as("20 threads × 50 cents = 1000 cents exactly — no deposit lost or double-counted")
                .isEqualTo(1000L);
        assertThat(result.getVersion()).isEqualTo(21); // 1 create + 20 deposits
    }

    // ─── ZERO TRUST: No Client-Controlled Version ─────────────────────────────

    @Test
    @DisplayName("[SECURITY] version injection: engine ignores version even if root.setVersion() is called externally")
    void shouldIgnoreExternallySetVersionOnSave() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Hacker");
        engine.save(root);
        UUID accountId = root.getId();

        // Attacker loads root (version 1), tampers with version to claim it's version 0,
        // hoping to save a duplicate version=1 event and corrupt the stream.
        AggregateRoot<BankAccountState> tampered = engine.load(accountId).orElseThrow();
        tampered.setVersion(0); // force expectedVersion = 0 - 1 = -1? No: uncommitted is empty here

        // After setVersion(0) with no uncommitted events → save() returns early (empty uncommitted)
        // No harm: attacker can't plant events without going through handleEvent()
        bankAccount.deposit(tampered, 999L, "tampered deposit");
        // Now: tampered.version = 0, uncommitted.size = 1
        // expectedVersion = 0 - 1 = -1
        // Phase 1: SELECT MAX = 1, currentVersion(1) > expectedVersion(-1) → CME
        // [SECURITY] Negative expectedVersion from tampered version is still caught by Phase 1
        assertThatThrownBy(() -> engine.save(tampered))
                .isInstanceOf(ConcurrentModificationException.class)
                .satisfies(e -> {
                    ConcurrentModificationException cme = (ConcurrentModificationException) e;
                    assertThat(cme.getAggregateId()).isEqualTo(accountId);
                });

        // DB must not have any tampered events
        List<StoredEvent> events = eventStore.load(accountId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventType()).isEqualTo("AccountCreated");
    }

    // ─── Defense in Depth: Payload Injection ──────────────────────────────────

    @Test
    @DisplayName("[SECURITY] SQL injection in event payload stored as literal — does not execute as SQL")
    void shouldStoreSqlInjectionPayloadAsLiteralText() {
        AggregateRoot<BankAccountState> root = BankAccount.create("'; DROP TABLE events; --");
        engine.save(root);

        // Events table must still exist and contain the event
        List<StoredEvent> events = eventStore.load(root.getId());
        assertThat(events).hasSize(1);

        // Table survived the injection attempt
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM events", Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(1);
    }
}
